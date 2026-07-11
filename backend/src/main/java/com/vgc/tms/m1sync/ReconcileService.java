package com.vgc.tms.m1sync;

import com.vgc.tms.common.ContentHash;
import com.vgc.tms.m1sync.SyncEntities.TestCaseEntity;
import com.vgc.tms.m1sync.SyncRepositories.TestCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * On-demand + scheduled reconciliation (REQ-M1-05, contract v1.3 §E3). Complements the per-run
 * {@code zero_change} anomaly: reconcile compares stored-TMS vs fresh-Polarion {@code contentHash}
 * across ALL mapped items — including the already-synced/overlap region (rev ≤ watermark) — the
 * C4/B4 carry-forward gate for silent-write-failure/drift on already-synced items.
 *
 * Two tiers (§E3, scheduled by {@link ReconcileScheduler}):
 *  - Tier-3 full-hash scan (nightly) → drift detection ≤24h.
 *  - Tier-2 row-count tripwire (hourly, count-only, no hashing) → gross drift/missing ≤1h.
 *
 * Perf (B4): the Polarion full fetch is PAGED (see {@link #PAGE_SIZE}) and compared per-batch so
 * 10k items are never all held in memory; the mock returns one page (REST impl paginates + caps
 * Polarion concurrency).
 */
@Service
public class ReconcileService {

    static final int PAGE_SIZE = 1000;   // B4: page 500–1k; stream/compare per-batch

    private final PolarionClient polarion;
    private final TestCaseRepository testCases;

    public ReconcileService(PolarionClient polarion, TestCaseRepository testCases) {
        this.polarion = polarion;
        this.testCases = testCases;
    }

    /** Tier-3 report. `missing` split by direction (B4): Polarion-has/TMS-missing vs TMS-has/Polarion-missing. */
    public record ReconcileReport(int matched, int mismatched,
                                  List<String> driftedPolarionIds,
                                  List<String> missingInTMS,      // in Polarion, not synced to TMS (sync gap)
                                  List<String> missingInPolarion, // in TMS, not in Polarion (orphan/deletion)
                                  Instant asOf) {
        public boolean clean() { return mismatched == 0 && missingInTMS.isEmpty() && missingInPolarion.isEmpty(); }
    }

    /** Tier-2 cheap tripwire — count-only, no hashing (§E3 ≤1h). */
    public record RowCountReport(long polarionCount, long tmsCount, long drift, Instant asOf) {
        public boolean tripped() { return drift != 0; }
    }

    @Transactional(readOnly = true)
    public ReconcileReport reconcile(Long projectId, String polarionProjectId) {
        int matched = 0, mismatched = 0;
        List<String> drifted = new ArrayList<>(), missingInTMS = new ArrayList<>();
        Set<String> seenPolarionIds = new HashSet<>();

        // Full scan, PAGED (B4 perf): compare per-batch, never hold all items at once.
        for (List<PolarionWorkItem> page : pagedFullFetch(polarionProjectId)) {
            for (PolarionWorkItem item : page) {
                if (!"testcase".equalsIgnoreCase(item.type())) continue;
                seenPolarionIds.add(item.polarionId());
                String fresh = ContentHash.of(item.fields());
                Optional<TestCaseEntity> stored = testCases.findByProjectIdAndPolarionId(projectId, item.polarionId());
                if (stored.isEmpty()) missingInTMS.add(item.polarionId());          // sync gap
                else if (!fresh.equals(stored.get().contentHash)) { mismatched++; drifted.add(item.polarionId()); }
                else matched++;
            }
        }
        // Reverse direction (B4 #4): TMS rows with no Polarion match = orphan/deletion.
        List<String> missingInPolarion = new ArrayList<>();
        for (TestCaseEntity tc : testCases.findByProjectId(projectId)) {
            if (!seenPolarionIds.contains(tc.polarionId)) missingInPolarion.add(tc.polarionId);
        }
        return new ReconcileReport(matched, mismatched, drifted, missingInTMS, missingInPolarion, Instant.now());
    }

    /** Tier-2 tripwire: compare item counts only (no hashing) — cheap, frequent. */
    @Transactional(readOnly = true)
    public RowCountReport rowCountTripwire(Long projectId, String polarionProjectId) {
        long polarionCount = polarion.countChangedSince(polarionProjectId, "0");   // all
        long tmsCount = testCases.countByProjectId(projectId);
        return new RowCountReport(polarionCount, tmsCount, Math.abs(polarionCount - tmsCount), Instant.now());
    }

    /** Paged full fetch. Mock returns a single page; REST impl paginates + caps concurrency (B4). */
    private Iterable<List<PolarionWorkItem>> pagedFullFetch(String polarionProjectId) {
        List<PolarionWorkItem> all = polarion.fetchChangedSince(polarionProjectId, "0");
        List<List<PolarionWorkItem>> pages = new ArrayList<>();
        for (int i = 0; i < all.size(); i += PAGE_SIZE) {
            pages.add(all.subList(i, Math.min(i + PAGE_SIZE, all.size())));
        }
        if (pages.isEmpty()) pages.add(List.of());
        return pages;
    }
}
