package com.vgc.tms.m1sync;

import com.vgc.tms.common.ContentHash;
import com.vgc.tms.m1sync.SyncEntities.TestCaseEntity;
import com.vgc.tms.m1sync.SyncRepositories.TestCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * On-demand reconciliation (REQ-M1-05). Complements the per-run {@code zero_change} anomaly:
 * where zero_change only covers NEW upstream deltas, reconcile compares stored-TMS vs fresh-Polarion
 * {@code contentHash} across ALL mapped items — **including the already-synced / overlap region**
 * (revision <= watermark). This is the C4/B4-owned control for silent-write-failure / drift on
 * already-synced items (RAID carry-forward gate). Full coverage, O(n) with covering indexes (B4).
 */
@Service
public class ReconcileService {

    private final PolarionClient polarion;
    private final TestCaseRepository testCases;

    public ReconcileService(PolarionClient polarion, TestCaseRepository testCases) {
        this.polarion = polarion;
        this.testCases = testCases;
    }

    /** Report over the full mapped set (not just the delta window). */
    public record ReconcileReport(int matched, int mismatched, int missing,
                                  List<String> driftedPolarionIds, Instant asOf) {
        public boolean clean() { return mismatched == 0 && missing == 0; }
    }

    @Transactional(readOnly = true)
    public ReconcileReport reconcile(Long projectId, String polarionProjectId) {
        // Full fetch (revision > 0 == all) — covers already-synced/overlap region, not just deltas.
        List<PolarionWorkItem> all = polarion.fetchChangedSince(polarionProjectId, "0");
        int matched = 0, mismatched = 0, missing = 0;
        List<String> drifted = new ArrayList<>();
        for (PolarionWorkItem item : all) {
            if (!"testcase".equalsIgnoreCase(item.type())) continue;   // requirement/defect added with their sync
            String fresh = ContentHash.of(item.fields());
            Optional<TestCaseEntity> stored = testCases.findByProjectIdAndPolarionId(projectId, item.polarionId());
            if (stored.isEmpty()) {
                missing++;                                             // upstream item never synced to TMS
            } else if (!fresh.equals(stored.get().contentHash)) {
                mismatched++; drifted.add(item.polarionId());         // silent drift on an already-synced item
            } else {
                matched++;
            }
        }
        return new ReconcileReport(matched, mismatched, missing, drifted, Instant.now());
    }
}
