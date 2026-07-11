package com.vgc.tms.m1sync;

import com.vgc.tms.common.ContentHash;
import com.vgc.tms.m1sync.SyncEntities.ConflictItemEntity;
import com.vgc.tms.m1sync.SyncEntities.TestCaseEntity;
import com.vgc.tms.m1sync.SyncRepositories.ConflictItemRepository;
import com.vgc.tms.m1sync.SyncRepositories.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Bidi write-back conflict tests (REQ-M1-03/04, TS-B-02) — B2 v1.3 E1/E2 predicate:
 * CONFLICT ⇔ Polarion moved (currentRevision != lastCommonRevision) AND TMS locally dirty
 *            (currentTmsHash != storedContentHash). No last-write-wins.
 */
class WriteBackServiceTest {

    private MockPolarionClient polarion;
    private TestCaseRepository testCases;
    private ConflictItemRepository conflicts;
    private WriteBackService svc;

    private static final Map<String, Object> SYNCED = Map.of("title", "orig", "definition", "d");
    private static final Map<String, Object> EDITED = Map.of("title", "tms-edit", "definition", "d");

    private TestCaseEntity syncedTc() {
        TestCaseEntity tc = new TestCaseEntity();
        tc.projectId = 1L; tc.polarionId = "A";
        tc.lastCommonRevision = "5";
        tc.contentHash = ContentHash.of(SYNCED);   // last-synced hash
        return tc;
    }

    @BeforeEach
    void setup() {
        polarion = new MockPolarionClient();
        testCases = mock(TestCaseRepository.class);
        conflicts = mock(ConflictItemRepository.class);
        when(testCases.save(any())).thenAnswer(a -> a.getArgument(0));
        when(conflicts.save(any())).thenAnswer(a -> a.getArgument(0));
        when(conflicts.findByItemPolarionIdAndStatus(anyString(), eq("open"))).thenReturn(List.of());
        svc = new WriteBackService(polarion, testCases, conflicts);
    }

    @Test
    void polarionOnlyChange_noFalseConflict_writesThrough() {   // TS-B-02 boundary: Polarion moved, TMS clean
        polarion.put(new PolarionWorkItem("A", "testcase", "7", SYNCED));  // Polarion at rev 7 != base 5
        TestCaseEntity tc = syncedTc();                                    // TMS NOT dirty (fields == synced)
        WriteBackService.Result r = svc.writeBack(1L, "PROJ", tc, SYNCED);
        assertEquals(WriteBackService.Result.WRITTEN, r, "Polarion-only change must not raise a false conflict");
        verify(conflicts, never()).save(any());
    }

    @Test
    void bothChanged_queuesConflict_noOverwrite() {            // TS-B-02: concurrent edit → conflict, no LWW
        polarion.put(new PolarionWorkItem("A", "testcase", "7", Map.of("title", "polarion-edit", "definition", "d")));
        TestCaseEntity tc = syncedTc();                        // Polarion moved (7!=5) AND TMS dirty (EDITED != synced)
        WriteBackService.Result r = svc.writeBack(1L, "PROJ", tc, EDITED);
        assertEquals(WriteBackService.Result.CONFLICT_QUEUED, r);
        verify(conflicts).save(any(ConflictItemEntity.class));   // queued
        // no-overwrite: Polarion still holds its own edit (we did not write EDITED over it)
        assertEquals("polarion-edit", polarion.fetchChangedSince("PROJ", "0").get(0).fields().get("title"));
    }

    @Test
    void openConflict_freezesAutoWrite() {                    // anti-thrash (REQ-M1-04)
        when(conflicts.findByItemPolarionIdAndStatus(eq("A"), eq("open")))
                .thenReturn(List.of(new ConflictItemEntity()));
        WriteBackService.Result r = svc.writeBack(1L, "PROJ", syncedTc(), EDITED);
        assertEquals(WriteBackService.Result.FROZEN_PENDING_CONFLICT, r);
    }
}
