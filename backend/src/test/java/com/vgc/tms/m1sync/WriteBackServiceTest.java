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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Bidi write-back tests (REQ-M1-03/04, TS-B-02) — B2 v1.3 §E1/E2:
 * predicate (no-LWW), REVISION-GUARDED write (TOCTOU / M1-T3), resolve re-base (no re-trigger).
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
        tc.contentHash = ContentHash.of(SYNCED);
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
    void polarionOnlyChange_noFalseConflict_writesThrough() {   // Polarion at base, TMS clean → no conflict
        polarion.put(new PolarionWorkItem("A", "testcase", "5", SYNCED));  // guard passes
        assertEquals(WriteBackService.Result.WRITTEN, svc.writeBack(1L, "PROJ", syncedTc(), SYNCED));
        verify(conflicts, never()).save(any());
    }

    @Test
    void bothChanged_queuesConflict_noOverwrite() {            // concurrent edit → conflict, no LWW
        polarion.put(new PolarionWorkItem("A", "testcase", "7", Map.of("title", "polarion-edit", "definition", "d")));
        assertEquals(WriteBackService.Result.CONFLICT_QUEUED, svc.writeBack(1L, "PROJ", syncedTc(), EDITED));
        verify(conflicts).save(any(ConflictItemEntity.class));
        assertEquals("polarion-edit", polarion.fetchChangedSince("PROJ", "0").get(0).fields().get("title"));
    }

    @Test
    void revisionChangesBetweenCheckAndWrite_conflictNotOverwrite() {   // TOCTOU (B4/C4 M1-T3)
        // Predicate sees no conflict (Polarion at base rev 5 → polarionMoved=false) though TMS is dirty;
        // a concurrent edit lands in the check→write window: the guarded write is REJECTED (null).
        PolarionClient racy = new PolarionClient() {
            public int countChangedSince(String p, String s) { return 0; }
            public List<PolarionWorkItem> fetchChangedSince(String p, String s) { return List.of(); }
            public String currentRevision(String p, String id) { return "5"; }               // == lastCommonRevision
            public String writeIfRevisionMatches(String p, PolarionWorkItem i, String exp) { return null; } // moved in window
        };
        WriteBackService s = new WriteBackService(racy, testCases, conflicts);
        assertEquals(WriteBackService.Result.CONFLICT_QUEUED, s.writeBack(1L, "PROJ", syncedTc(), EDITED));
        verify(conflicts).save(any(ConflictItemEntity.class));   // no force-overwrite
    }

    @Test
    void resolvedConflict_doesNotReTrigger() {                 // §E2 re-base both anchors
        polarion.put(new PolarionWorkItem("A", "testcase", "9", Map.of("title", "polarion-edit", "definition", "d")));
        TestCaseEntity tc = syncedTc();
        Map<String, Object> resolved = Map.of("title", "agreed", "definition", "d");
        when(conflicts.findById(1L)).thenReturn(Optional.of(new ConflictItemEntity()));
        svc.resolve(1L, "PROJ", tc, resolved);
        assertEquals(tc.revision, tc.lastCommonRevision, "lastCommonRevision re-based to new Polarion rev");
        assertEquals(ContentHash.of(resolved), tc.contentHash, "storedContentHash re-based to resolved TMS state");
        // subsequent write-back of the resolved fields sees NO conflict (predicate clean) → no thrash
        assertEquals(WriteBackService.Result.WRITTEN, svc.writeBack(1L, "PROJ", tc, resolved));
    }

    @Test
    void openConflict_freezesAutoWrite() {                    // anti-thrash (REQ-M1-04)
        when(conflicts.findByItemPolarionIdAndStatus(eq("A"), eq("open")))
                .thenReturn(List.of(new ConflictItemEntity()));
        assertEquals(WriteBackService.Result.FROZEN_PENDING_CONFLICT, svc.writeBack(1L, "PROJ", syncedTc(), EDITED));
    }
}
