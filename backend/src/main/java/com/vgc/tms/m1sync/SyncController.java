package com.vgc.tms.m1sync;

import com.vgc.tms.m1sync.SyncEntities.SyncRunEntity;
import com.vgc.tms.m1sync.SyncRepositories.SyncRunRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal Sync API (IF-1 v1.2 §A4) — Sprint-1 subset: run + status.
 * reconcile / conflicts / errors / config land in S2 (bidi + reconciler) and PR#2 (config).
 * On-demand sync is admin/test-lead only (RBAC deny-by-default, PR#2); every run is audited via the service.
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final ReadSyncService readSync;
    private final SyncRunRepository runs;
    private final ReconcileService reconcile;

    public SyncController(ReadSyncService readSync, SyncRunRepository runs, ReconcileService reconcile) {
        this.readSync = readSync;
        this.runs = runs;
        this.reconcile = reconcile;
    }

    /** GET /api/sync/{projectId}/reconcile — on-demand full reconcile (REQ-M1-05); covers already-synced region. */
    @GetMapping("/{projectId}/reconcile")
    public ResponseEntity<Map<String, Object>> reconcile(@PathVariable Long projectId,
                                                         @RequestParam(defaultValue = "") String polarionProject) {
        String polProj = polarionProject.isBlank() ? String.valueOf(projectId) : polarionProject;
        ReconcileService.ReconcileReport r = reconcile.reconcile(projectId, polProj);
        return ResponseEntity.ok(Map.of(
                "matched", r.matched(), "mismatched", r.mismatched(),
                "driftedPolarionIds", r.driftedPolarionIds(),
                "missingInTMS", r.missingInTMS(), "missingInPolarion", r.missingInPolarion(),
                "clean", r.clean(), "asOf", r.asOf()));
    }

    /** POST /api/sync/{projectId}/run — trigger an incremental read-sync (REQ-M1-02). 202 + runId. */
    @PostMapping("/{projectId}/run")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR','TEST_LEAD')")
    public ResponseEntity<Map<String, Object>> run(@PathVariable Long projectId,
                                                   @RequestParam(defaultValue = "") String polarionProject) {
        String polProj = polarionProject.isBlank() ? String.valueOf(projectId) : polarionProject;
        SyncRunEntity r = readSync.sync(projectId, polProj);
        return ResponseEntity.accepted().body(Map.of(
                "runId", r.id, "read", r.read, "written", r.written,
                "failed", r.failed, "expectedDelta", r.expectedDelta, "anomalyFlags", r.anomalyFlags));
    }

    /** GET /api/sync/{projectId}/status — last run health record (REQ-M1-05). */
    @GetMapping("/{projectId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable Long projectId) {
        SyncRunEntity r = runs.findFirstByProjectIdOrderByStartedAtDesc(projectId);
        if (r == null) return ResponseEntity.ok(Map.of("projectId", projectId, "lastRun", "none"));
        return ResponseEntity.ok(Map.of(
                "runId", r.id, "startedAt", r.startedAt, "durationMs", r.durationMs,
                "read", r.read, "written", r.written, "failed", r.failed,
                "expectedDelta", r.expectedDelta, "anomalyFlags", r.anomalyFlags));
    }
}
