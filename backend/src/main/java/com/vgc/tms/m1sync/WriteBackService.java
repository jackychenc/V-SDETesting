package com.vgc.tms.m1sync;

import com.vgc.tms.common.ContentHash;
import com.vgc.tms.m1sync.SyncEntities.*;
import com.vgc.tms.m1sync.SyncRepositories.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Bidirectional write-back + revision conflict detection (REQ-M1-03/04, TS-B-02).
 * Implements B2 contract v1.3 PART E1/E2 authoritative conflict predicate:
 *   CONFLICT  ⇔  polarion.currentRevision != lastCommonRevision   (Polarion moved)
 *             AND currentTmsHash          != storedContentHash    (TMS also locally dirty)
 * Excludes false positives (Polarion-only change → write proceeds) and misses (TMS change → caught).
 * On conflict: queue a ConflictItem, DO NOT WRITE (never last-write-wins). Items with an open
 * conflict are frozen from auto-write until human resolution (anti-thrash).
 */
@Service
public class WriteBackService {

    private final PolarionClient polarion;
    private final TestCaseRepository testCases;
    private final ConflictItemRepository conflicts;

    public WriteBackService(PolarionClient polarion, TestCaseRepository testCases, ConflictItemRepository conflicts) {
        this.polarion = polarion;
        this.testCases = testCases;
        this.conflicts = conflicts;
    }

    public enum Result { WRITTEN, CONFLICT_QUEUED, FROZEN_PENDING_CONFLICT }

    /**
     * Write the given (locally-edited) fields back to Polarion for a synced test case.
     * @param currentFields the current TMS field-set (used to compute the local dirty hash)
     */
    @Transactional
    public Result writeBack(Long projectId, String polarionProjectId, TestCaseEntity tc, Map<String, Object> currentFields) {
        // anti-thrash: never auto-write an item that already has an open conflict (REQ-M1-04)
        if (!conflicts.findByItemPolarionIdAndStatus(tc.polarionId, "open").isEmpty()) {
            return Result.FROZEN_PENDING_CONFLICT;
        }

        String currentRevision = polarion.currentRevision(polarionProjectId, tc.polarionId);
        boolean polarionMoved = currentRevision != null && !currentRevision.equals(tc.lastCommonRevision);
        String currentTmsHash = ContentHash.of(currentFields);
        boolean tmsDirty = !currentTmsHash.equals(tc.contentHash);

        // E1/E2 authoritative predicate — BOTH sides moved ⇒ conflict (no LWW).
        if (polarionMoved && tmsDirty) {
            ConflictItemEntity c = new ConflictItemEntity();
            c.itemPolarionId = tc.polarionId;
            c.fieldDiffs = "{\"base\":\"" + safe(tc.lastCommonRevision) + "\",\"polarionRev\":\"" + safe(currentRevision)
                    + "\",\"tmsHash\":\"" + currentTmsHash + "\",\"storedHash\":\"" + safe(tc.contentHash) + "\"}";
            conflicts.save(c);
            return Result.CONFLICT_QUEUED;   // DO NOT WRITE
        }

        // No conflict (Polarion-only change, or clean) → write, refresh base + hash.
        String newRev = polarion.write(polarionProjectId, new PolarionWorkItem(tc.polarionId, "testcase", null, currentFields));
        tc.revision = newRev;
        tc.lastCommonRevision = newRev;
        tc.contentHash = currentTmsHash;
        tc.sourceRevision = newRev;
        tc.lastSyncedAt = Instant.now();
        testCases.save(tc);
        return Result.WRITTEN;
    }

    /** Human resolution closes the conflict and re-bases both sides (REQ-M1-04). */
    @Transactional
    public void resolve(Long conflictId, String resolutionJson) {
        conflicts.findById(conflictId).ifPresent(c -> {
            c.status = "resolved";
            c.resolution = resolutionJson;
            conflicts.save(c);
        });
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
