package com.vgc.tms.m1sync;

import com.vgc.tms.common.ContentHash;
import com.vgc.tms.m1sync.SyncEntities.*;
import com.vgc.tms.m1sync.SyncRepositories.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * M1 incremental read-sync (Polarion→TMS), REQ-M1-01/02/05/06. Implements the design-of-record
 * with B4's correctness guards:
 *  - #1 expectedDelta uses the INDEPENDENT probe {@link PolarionClient#countChangedSince} (not fetch.size).
 *  - #2 watermark advances only through the CONTIGUOUS all-success prefix (failed items re-fetch next run).
 * Bidirectional write-back, conflict queue and error-queue retry are Sprint-2.
 */
@Service
public class ReadSyncService {

    private final PolarionClient polarion;
    private final TestCaseRepository testCases;
    private final SyncStateRepository states;
    private final SyncRunRepository runs;

    public ReadSyncService(PolarionClient polarion, TestCaseRepository testCases,
                           SyncStateRepository states, SyncRunRepository runs) {
        this.polarion = polarion;
        this.testCases = testCases;
        this.states = states;
        this.runs = runs;
    }

    /** Run one incremental read-sync for a project. On-demand (POST /run) or scheduled (REQ-M1-02). */
    @Transactional
    public SyncRunEntity sync(Long projectId, String polarionProjectId) {
        long t0 = System.currentTimeMillis();
        SyncStateEntity state = states.findById(projectId).orElseGet(() -> {
            SyncStateEntity s = new SyncStateEntity(); s.projectId = projectId; s.watermark = "0"; return s;
        });
        String sinceRev = lookbackFrom(state.watermark, state.overlapLookback);

        // B4#1 — INDEPENDENT delta probe (separate query path from the extract below).
        int expectedDelta = polarion.countChangedSince(polarionProjectId, sinceRev);

        List<PolarionWorkItem> items = polarion.fetchChangedSince(polarionProjectId, sinceRev);
        items.sort(Comparator.comparingLong(i -> parse(i.revision())));   // process in revision order

        int written = 0, failed = 0;
        String newWatermark = state.watermark;
        boolean contiguous = true;                                        // B4#2 no-skip-on-failure
        for (PolarionWorkItem item : items) {
            try {
                if ("testcase".equalsIgnoreCase(item.type())) upsertTestCase(projectId, item);
                // requirement/defect: same idempotent-upsert pattern, added with M2/M3.
                written++;
                if (contiguous) newWatermark = item.revision();           // advance only through success prefix
            } catch (RuntimeException ex) {
                failed++;
                contiguous = false;                                       // stop advancing; item re-fetches next cycle
            }
        }

        SyncRunEntity run = new SyncRunEntity();
        run.projectId = projectId;
        run.startedAt = Instant.now();
        run.read = items.size();
        run.written = written;
        run.failed = failed;
        run.expectedDelta = expectedDelta;
        run.anomalyFlags = detectAnomalies(projectId, written, failed, expectedDelta);
        run.durationMs = System.currentTimeMillis() - t0;

        state.watermark = newWatermark;
        states.save(state);
        return runs.save(run);
    }

    /** Idempotent upsert keyed on (projectId, polarionId) — safe re-run (REQ-M1-06). */
    private void upsertTestCase(Long projectId, PolarionWorkItem item) {
        TestCaseEntity tc = testCases.findByProjectIdAndPolarionId(projectId, item.polarionId())
                .orElseGet(TestCaseEntity::new);
        tc.projectId = projectId;
        tc.polarionId = item.polarionId();
        tc.title = str(item.fields().get("title"));
        tc.definition = str(item.fields().get("definition"));
        tc.revision = item.revision();
        tc.sourceRevision = item.revision();
        tc.contentHash = ContentHash.of(item.fields());
        tc.lastSyncedAt = Instant.now();
        testCases.save(tc);
    }

    /** B4 anomaly signals (alertable). zero_change = "success, wrote nothing" while upstream had deltas. */
    private String detectAnomalies(Long projectId, int written, int failed, int expectedDelta) {
        StringBuilder flags = new StringBuilder();
        if (written == 0 && expectedDelta > 0) append(flags, "zero_change");   // relies on independent probe (B4#1)
        if (failed > 0) {
            SyncRunEntity prev = runs.findFirstByProjectIdOrderByStartedAtDesc(projectId);
            if (prev != null && prev.failed > 0) append(flags, "consecutive_failure");
        }
        return flags.toString();
    }

    // --- helpers ---
    private static String lookbackFrom(String watermark, int overlap) {
        long w = parse(watermark) - Math.max(0, overlap);
        return String.valueOf(Math.max(0, w));
    }
    private static long parse(String rev) {
        if (rev == null || rev.isBlank()) return 0L;
        try { return Long.parseLong(rev); } catch (NumberFormatException e) { return 0L; }
    }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static void append(StringBuilder sb, String f) { if (sb.length() > 0) sb.append(','); sb.append(f); }
}
