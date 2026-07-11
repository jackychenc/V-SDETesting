package com.vgc.tms.m5admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin sync-config endpoint (REQ-M5-04 — versioned/revertible config; REQ-M5-01 RBAC).
 * PR#2 skeleton: demonstrates the deny-by-default + audit contract on a real mutating endpoint.
 * The persisted, versioned SyncConfig (JSONB) lands with the schema post-G3; here the PUT is a
 * stub that exercises the authorization + audit path (REQ-M5-03), testable now (TS-B-06).
 */
@RestController
@RequestMapping("/api/sync/config")
public class SyncConfigController {

    private final AuditService audit;

    public SyncConfigController(AuditService audit) {
        this.audit = audit;
    }

    /** Read config — any authenticated role may view (least surprise; write is admin-only). */
    @GetMapping("/{projectId}")
    public Map<String, Object> get(@PathVariable String projectId) {
        return Map.of("projectId", projectId, "version", 0, "note", "stub — persisted config post-G3");
    }

    /** Update config — ADMINISTRATOR only (deny-by-default enforced centrally + here). Audited. */
    @PutMapping("/{projectId}")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public Map<String, Object> update(@PathVariable String projectId,
                                      @RequestBody Map<String, Object> body,
                                      @AuthenticationPrincipal UserDetails user) {
        audit.record(AuditEvent.allow(user != null ? user.getUsername() : "unknown",
                "SYNC_CONFIG_UPDATE", "/api/sync/config/" + projectId));
        return Map.of("projectId", projectId, "accepted", true, "note", "stub — versioned persistence post-G3");
    }
}
