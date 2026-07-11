package com.vgc.tms.m1sync;

import com.vgc.tms.common.ContentHash;
import com.vgc.tms.m1sync.SyncEntities.TestCaseEntity;
import com.vgc.tms.m1sync.SyncRepositories.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Reconcile tests (REQ-M1-05, v1.3 §E3). Proves already-synced drift detection (C4 gate),
 * BOTH-direction missing (B4 #4), and the Tier-2 row-count tripwire.
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
        e.projectId = 1L; e.polarionId = pid; e.contentHash = ContentHash.of(syncedFields);
        return e;
    }

    @BeforeEach
    void setup() {
        polarion = new MockPolarionClient();
        testCases = mock(TestCaseRepository.class);
        when(testCases.findByProjectId(1L)).thenReturn(List.of());   // default: no TMS-only orphans
        svc = new ReconcileService(polarion, testCases);
    }

    @Test
    void detectsSilentDriftOnAlreadySyncedItem() {   // C4 gate: already-synced/overlap coverage
        when(testCases.findByProjectIdAndPolarionId(1L, "A"))
                .thenReturn(Optional.of(stored("A", Map.of("title", "orig", "definition", "d"))));
        polarion.put(tc("A", "3", "DRIFTED"));
        var r = svc.reconcile(1L, "PROJ");
        assertEquals(1, r.mismatched());
        assertTrue(r.driftedPolarionIds().contains("A"));
        assertFalse(r.clean());
    }

    @Test
    void missingInTMS_polarionHasItemNeverSynced() {   // forward gap (B4 #4)
        when(testCases.findByProjectIdAndPolarionId(1L, "A")).thenReturn(Optional.empty());
        polarion.put(tc("A", "1", "upstream-only"));
        var r = svc.reconcile(1L, "PROJ");
        assertTrue(r.missingInTMS().contains("A"));
        assertTrue(r.missingInPolarion().isEmpty());
        assertFalse(r.clean());
    }

    @Test
    void missingInPolarion_tmsOrphanWithNoPolarionMatch() {   // reverse gap (B4 #4) — orphan/deletion
        TestCaseEntity orphan = stored("Z", Map.of("title", "gone", "definition", "d"));
        when(testCases.findByProjectId(1L)).thenReturn(List.of(orphan));   // TMS has Z...
        // ...Polarion has nothing → Z is an orphan
        var r = svc.reconcile(1L, "PROJ");
        assertTrue(r.missingInPolarion().contains("Z"));
        assertTrue(r.missingInTMS().isEmpty());
        assertFalse(r.clean());
    }

    @Test
    void rowCountTripwire_detectsCountDrift() {   // Tier-2 cheap tripwire (§E3 ≤1h)
        polarion.put(tc("A", "1", "a")); polarion.put(tc("B", "2", "b"));   // Polarion has 2
        when(testCases.countByProjectId(1L)).thenReturn(1L);                 // TMS has 1
        var r = svc.rowCountTripwire(1L, "PROJ");
        assertEquals(2, r.polarionCount());
        assertEquals(1, r.tmsCount());
        assertEquals(1, r.drift());
        assertTrue(r.tripped());
    }
}
