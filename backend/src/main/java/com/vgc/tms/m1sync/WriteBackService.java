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
            queueConflict(tc, currentRevision, currentTmsHash, currentFields);
            return Result.CONFLICT_QUEUED;   // DO NOT WRITE
        }

        // No conflict at check time → REVISION-GUARDED write (§E1): write only if Polarion still at
        // `currentRevision`. If a concurrent edit landed in the check→write window, the write is
        // rejected (null) → queue a conflict, NEVER force-overwrite (closes TOCTOU / M1-T3).
        String newRev = polarion.writeIfRevisionMatches(polarionProjectId,
                new PolarionWorkItem(tc.polarionId, "testcase", null, currentFields), currentRevision);
        if (newRev == null) {
            queueConflict(tc, polarion.currentRevision(polarionProjectId, tc.polarionId), currentTmsHash, currentFields);
            return Result.CONFLICT_QUEUED;   // concurrent edit in the window → conflict, no overwrite
        }
        tc.revision = newRev;
        tc.lastCommonRevision = newRev;       // re-base
        tc.contentHash = currentTmsHash;
        tc.sourceRevision = newRev;
        tc.lastSyncedAt = Instant.now();
        testCases.save(tc);
        return Result.WRITTEN;
    }

    /** 3-way conflict record (§E2 fieldDiffs{tmsValue, polarionValue, baseValue}) for human resolution. */
    private void queueConflict(TestCaseEntity tc, String polarionRev, String tmsHash, Map<String, Object> tmsFields) {
        ConflictItemEntity c = new ConflictItemEntity();
        c.itemPolarionId = tc.polarionId;
        c.fieldDiffs = "{\"baseRevision\":\"" + safe(tc.lastCommonRevision) + "\",\"baseHash\":\"" + safe(tc.contentHash)
                + "\",\"polarionRevision\":\"" + safe(polarionRev) + "\",\"tmsValue\":\"" + safe(String.valueOf(tmsFields))
                + "\",\"tmsHash\":\"" + tmsHash + "\"}";
        conflicts.save(c);
    }

    /**
     * Human resolution (§E2): write the chosen value to both sides and RE-BASE both anchors —
     * lastCommonRevision = post-resolution Polarion revision, storedContentHash = resolved TMS hash.
     * Without this the next sync would re-detect the same conflict (thrash).
     */
    @Transactional
    public void resolve(Long conflictId, String polarionProjectId, TestCaseEntity tc, Map<String, Object> resolvedFields) {
        conflicts.findById(conflictId).ifPresent(c -> {
            // force-write the human-chosen resolution (bypasses the guard — this IS the authoritative value)
            String newRev = polarion.writeIfRevisionMatches(polarionProjectId,
                    new PolarionWorkItem(tc.polarionId, "testcase", null, resolvedFields),
                    polarion.currentRevision(polarionProjectId, tc.polarionId));
            tc.revision = newRev;
            tc.lastCommonRevision = newRev;                        // re-base BOTH anchors...
            tc.contentHash = ContentHash.of(resolvedFields);       // ...so the conflict does not re-trigger
            tc.lastSyncedAt = Instant.now();
            testCases.save(tc);
            c.status = "resolved";
            c.resolution = String.valueOf(resolvedFields);
            conflicts.save(c);
        });
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
