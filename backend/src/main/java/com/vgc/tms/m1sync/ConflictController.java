package com.vgc.tms.m1sync;

import com.vgc.tms.m1sync.SyncEntities.ConflictItemEntity;
import com.vgc.tms.m1sync.SyncRepositories.ConflictItemRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Conflict queue API (REQ-M1-04, IF-1 v1.2 §A4). Human resolution only — no auto last-write-wins.
 * List is visible to authenticated users; resolve is Test Lead / Administrator (RBAC, PR#2).
 */
@RestController
@RequestMapping("/api/sync/conflicts")
public class ConflictController {

    private final ConflictItemRepository conflicts;
    private final WriteBackService writeBack;

    public ConflictController(ConflictItemRepository conflicts, WriteBackService writeBack) {
        this.conflicts = conflicts;
        this.writeBack = writeBack;
    }

    /** GET /api/sync/conflicts — open conflicts awaiting human resolution (side-by-side diff in fieldDiffs). */
    @GetMapping
    public List<ConflictItemEntity> open() {
        return conflicts.findByStatus("open");
    }

    /** POST /api/sync/conflicts/{id}/resolve — human resolution; re-bases both sides. */
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('TEST_LEAD','ADMINISTRATOR')")
    public Map<String, Object> resolve(@PathVariable Long id, @RequestBody Map<String, Object> resolution) {
        writeBack.resolve(id, String.valueOf(resolution));
        return Map.of("id", id, "status", "resolved");
    }
}
