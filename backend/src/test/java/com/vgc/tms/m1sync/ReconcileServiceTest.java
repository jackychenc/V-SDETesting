package com.vgc.tms.m1sync;

import com.vgc.tms.common.ContentHash;
import com.vgc.tms.m1sync.SyncEntities.TestCaseEntity;
import com.vgc.tms.m1sync.SyncRepositories.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Reconcile tests (REQ-M1-05). Proves the on-demand reconcile catches silent drift on an
 * ALREADY-SYNCED item (the C4 carried-forward gate the strict per-run zero_change defers to S2).
 */
class ReconcileServiceTest {

    private MockPolarionClient polarion;
    private TestCaseRepository testCases;
    private ReconcileService svc;

    private static PolarionWorkItem tc(String id, String rev, String title) {
        return new PolarionWorkItem(id, "testcase", rev, Map.of("title", title, "definition", "d"));
    }
    private static TestCaseEntity stored(String pid, Map<String, Object> syncedFields) {
        TestCaseEntity e = new TestCaseEntity();
        e.polarionId = pid; e.contentHash = ContentHash.of(syncedFields);
        return e;
    }

    @BeforeEach
    void setup() {
        polarion = new MockPolarionClient();
        testCases = mock(TestCaseRepository.class);
        svc = new ReconcileService(polarion, testCases);
    }

    @Test
    void detectsSilentDriftOnAlreadySyncedItem() {   // C4 gate: overlap/already-synced coverage
        // TMS stored "A" with the ORIGINAL field-set hash...
        when(testCases.findByProjectIdAndPolarionId(1L, "A"))
                .thenReturn(Optional.of(stored("A", Map.of("title", "orig", "definition", "d"))));
        // ...but Polarion's current "A" has drifted (title changed) — a silent already-synced drift.
        polarion.put(tc("A", "3", "DRIFTED"));
        var r = svc.reconcile(1L, "PROJ");
        assertEquals(1, r.mismatched());
        assertTrue(r.driftedPolarionIds().contains("A"));
        assertFalse(r.clean());
    }

    @Test
    void cleanWhenHashesMatch() {
        Map<String, Object> fields = Map.of("title", "same", "definition", "d");
        when(testCases.findByProjectIdAndPolarionId(1L, "A")).thenReturn(Optional.of(stored("A", fields)));
        polarion.put(new PolarionWorkItem("A", "testcase", "3", fields));  // identical → same hash
        var r = svc.reconcile(1L, "PROJ");
        assertEquals(1, r.matched());
        assertEquals(0, r.mismatched());
        assertTrue(r.clean());
    }

    @Test
    void flagsMissingItemNeverSynced() {
        when(testCases.findByProjectIdAndPolarionId(1L, "A")).thenReturn(Optional.empty());
        polarion.put(tc("A", "1", "upstream-only"));
        var r = svc.reconcile(1L, "PROJ");
        assertEquals(1, r.missing());
        assertFalse(r.clean());
    }
}
