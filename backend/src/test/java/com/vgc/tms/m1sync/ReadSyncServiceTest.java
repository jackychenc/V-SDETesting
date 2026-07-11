package com.vgc.tms.m1sync;

import com.vgc.tms.m1sync.SyncEntities.*;
import com.vgc.tms.m1sync.SyncRepositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M1 read-sync unit tests. Covers [REQ-M1-02-AC1] / TS-B-01 (delta-only + health record) and
 * B4's two correctness guards: #1 independent-probe zero_change anomaly, #2 watermark stops at
 * the contiguous all-success prefix (no silent drop — REQ-M1-06).
 */
class ReadSyncServiceTest {

    private MockPolarionClient polarion;
    private TestCaseRepository testCases;
    private SyncStateRepository states;
    private SyncRunRepository runs;
    private ErrorQueueService errorQueue;
    private ReadSyncService svc;

    private static PolarionWorkItem tc(String id, String rev) {
        return new PolarionWorkItem(id, "testcase", rev, Map.of("title", "TC " + id, "definition", "d" + rev));
    }

    @BeforeEach
    void setup() {
        polarion = new MockPolarionClient();
        testCases = mock(TestCaseRepository.class);
        states = mock(SyncStateRepository.class);
        runs = mock(SyncRunRepository.class);
        when(testCases.findByProjectIdAndPolarionId(anyLong(), anyString())).thenReturn(Optional.empty());
        when(testCases.save(any())).thenAnswer(a -> a.getArgument(0));
        when(states.findById(anyLong())).thenReturn(Optional.empty());
        when(states.save(any())).thenAnswer(a -> a.getArgument(0));
        when(runs.save(any())).thenAnswer(a -> a.getArgument(0));
        errorQueue = mock(ErrorQueueService.class);
        svc = new ReadSyncService(polarion, testCases, states, runs, errorQueue);
    }

    @Test
    void firstSync_writesAll_andWritesHealthRecord() {   // TS-B-01
        polarion.put(tc("A", "1")); polarion.put(tc("B", "2")); polarion.put(tc("C", "3"));
        SyncRunEntity run = svc.sync(1L, "PROJ");
        assertEquals(3, run.read);
        assertEquals(3, run.written);
        assertEquals(0, run.failed);
        verify(runs).save(any());                        // per-run health record written
    }

    @Test
    void secondSync_transfersOnlyDeltas() {              // [REQ-M1-02-AC1]
        // watermark already at rev 3, overlapLookback 0 → only rev>3 fetched
        SyncStateEntity st = new SyncStateEntity(); st.projectId = 1L; st.watermark = "3"; st.overlapLookback = 0;
        when(states.findById(1L)).thenReturn(Optional.of(st));
        polarion.put(tc("A", "1")); polarion.put(tc("B", "2")); polarion.put(tc("C", "3"));
        polarion.put(tc("D", "4")); polarion.put(tc("E", "5"));  // 2 new
        SyncRunEntity run = svc.sync(1L, "PROJ");
        assertEquals(2, run.read, "only the 2 items above the watermark");
        assertEquals(2, run.written);
        assertEquals("5", st.watermark, "watermark advanced to highest synced revision");
    }

    @Test
    void silentFailure_zeroChangeAnomaly_viaIndependentProbe() {   // TS-B-03 (B4#1)
        // Independent probe reports 5 changed, but the extract returns nothing ("success, writes nothing").
        PolarionClient faulty = new PolarionClient() {
            public int countChangedSince(String p, String s) { return 5; }             // independent count > 0
            public List<PolarionWorkItem> fetchChangedSince(String p, String s) { return List.of(); } // writes nothing
            public String currentRevision(String p, String id) { return null; }
            public String writeIfRevisionMatches(String p, PolarionWorkItem i, String r) { return null; }
        };
        ReadSyncService s = new ReadSyncService(faulty, testCases, states, runs, errorQueue);
        SyncRunEntity run = s.sync(1L, "PROJ");
        assertEquals(0, run.written);
        assertEquals(5, run.expectedDelta);
        assertTrue(run.anomalyFlags.contains("zero_change"),
                "independent probe must surface the silent-sync-failure anomaly");
    }

    @Test
    void overlapItems_noChange_noFalseZeroChange() {   // B4 idle-run guard: strict-bound expectedDelta
        // watermark at 5 with overlapLookback 2 → fetch re-reads rev>3 (overlap), but NO genuinely-new
        // upstream revision (nothing > 5). Strict expectedDelta must be 0 → zero_change must NOT fire.
        SyncStateEntity st = new SyncStateEntity(); st.projectId = 1L; st.watermark = "5"; st.overlapLookback = 2;
        when(states.findById(1L)).thenReturn(Optional.of(st));
        polarion.put(tc("D", "4")); polarion.put(tc("E", "5"));  // both <= watermark; overlap re-fetch only
        SyncRunEntity run = svc.sync(1L, "PROJ");
        assertEquals(0, run.expectedDelta, "strict probe: no genuinely-new upstream revision");
        assertFalse(run.anomalyFlags.contains("zero_change"), "idle run must not false-fire zero_change");
    }

    @Test
    void watermarkStopsAtContiguousSuccessPrefix_onFailure() {     // REQ-M1-06 (B4#2)
        SyncStateEntity st = new SyncStateEntity(); st.projectId = 1L; st.watermark = "0"; st.overlapLookback = 0;
        when(states.findById(1L)).thenReturn(Optional.of(st));
        // item at rev 2 fails to persist; rev 1 ok, rev 3 ok (but above the failure)
        when(testCases.save(argThat((TestCaseEntity e) -> e != null && "BAD".equals(e.polarionId))))
                .thenThrow(new RuntimeException("simulated persist failure"));
        polarion.put(tc("OK1", "1")); polarion.put(tc("BAD", "2")); polarion.put(tc("OK3", "3"));
        SyncRunEntity run = svc.sync(1L, "PROJ");
        assertEquals(1, run.failed);
        assertEquals("1", st.watermark, "watermark must NOT jump past the failed item at rev 2");
        // REQ-M1-06 §E4: the failed item is enqueued to the error queue, never silently dropped
        verify(errorQueue).enqueue(any(), eq("BAD"), any());
    }

    @Test
    void sync_toleratesImmutableFetchResult_andSortsDefensiveCopy() {   // B4/C1 robustness regression
        // A real PolarionClient may back its result with an UNMODIFIABLE list (Stream.toList(),
        // List.of(), List.copyOf(), Collections.unmodifiableList()). sync() must sort a DEFENSIVE COPY,
        // never the caller-owned list, or it throws UnsupportedOperationException and crashes the sync.
        // (Guards the fix for the immutable-list-sort bug the TS-B-03 faulty stub first surfaced.)
        PolarionClient immutableClient = new PolarionClient() {
            public int countChangedSince(String p, String s) { return 2; }
            public List<PolarionWorkItem> fetchChangedSince(String p, String s) {
                return List.of(tc("B", "2"), tc("A", "1"));   // immutable + deliberately out of revision order
            }
            public String currentRevision(String p, String id) { return null; }
            public String writeIfRevisionMatches(String p, PolarionWorkItem i, String r) { return null; }
        };
        ReadSyncService s = new ReadSyncService(immutableClient, testCases, states, runs, errorQueue);
        SyncRunEntity run = assertDoesNotThrow(() -> s.sync(1L, "PROJ"),
                "immutable fetchChangedSince() return must not crash sync (defensive-copy sort)");
        assertEquals(2, run.read);
        assertEquals(2, run.written, "both items processed despite immutable input");
        assertEquals(0, run.failed);
    }
}
