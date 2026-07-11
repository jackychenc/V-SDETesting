package com.vgc.tms.m1sync;

import com.vgc.tms.m1sync.SyncEntities.ConflictItemEntity;
import com.vgc.tms.m1sync.SyncEntities.TestCaseEntity;
import com.vgc.tms.m1sync.SyncRepositories.ConflictItemRepository;
import com.vgc.tms.m1sync.SyncRepositories.TestCaseRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Conflict queue API (REQ-M1-04, IF-1 v1.2 §A4). Human resolution only — no auto last-write-wins.
 * List is visible to authenticated users; resolve is Test Lead / Administrator (RBAC, PR#2).
 */
@RestController
@RequestMapping("/api/sync/conflicts")
public class ConflictController {

    private final ConflictItemRepository conflicts;
    private final TestCaseRepository testCases;
    private final WriteBackService writeBack;

    public ConflictController(ConflictItemRepository conflicts, TestCaseRepository testCases, WriteBackService writeBack) {
        this.conflicts = conflicts;
        this.testCases = testCases;
        this.writeBack = writeBack;
    }

    /** GET /api/sync/conflicts — open conflicts awaiting human resolution (3-way diff in fieldDiffs). */
    @GetMapping
    public List<ConflictItemEntity> open() {
        return conflicts.findByStatus("open");
    }

    /**
     * POST /api/sync/conflicts/{id}/resolve — human resolution; re-bases both anchors (§E2).
     * Body: {"projectId":.., "polarionProject":"..", "resolvedFields":{...}}
     */
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('TEST_LEAD','ADMINISTRATOR')")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        ConflictItemEntity c = conflicts.findById(id).orElse(null);
        if (c == null) return ResponseEntity.notFound().build();
        Long projectId = Long.valueOf(String.valueOf(body.get("projectId")));
        String polProj = String.valueOf(body.getOrDefault("polarionProject", String.valueOf(projectId)));
        Map<String, Object> resolvedFields = (Map<String, Object>) body.get("resolvedFields");
        Optional<TestCaseEntity> tc = testCases.findByProjectIdAndPolarionId(projectId, c.itemPolarionId);
        if (tc.isEmpty()) return ResponseEntity.notFound().build();
        writeBack.resolve(id, polProj, tc.get(), resolvedFields);
        return ResponseEntity.ok(Map.of("id", id, "status", "resolved"));
    }
}
