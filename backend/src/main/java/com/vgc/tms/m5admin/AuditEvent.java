package com.vgc.tms.m5admin;

import java.time.Instant;

/**
 * Audit record — who / when / what (REQ-M5-03; maps TS-B-06, TS-B-10).
 * Covers admin actions, sync-config changes, conflict resolutions, exports.
 *
 * PR#2: domain record + emission hook. Persistence to the `audit_event` table lands with
 * the Flyway schema post-G3 (B2 data model entity `AuditEvent`); until then LoggingAuditService
 * emits to the audit log so the hook is exercised and testable now.
 *
 * @param actor    authenticated principal (username / service id)
 * @param action   e.g. "SYNC_CONFIG_UPDATE", "RUN_CLOSE", "CONFLICT_RESOLVE", "EXPORT"
 * @param resource target resource id/path
 * @param outcome  ALLOW | DENY (denied attempts are audited too — TS-B-06)
 * @param at       event time (UTC — NFR-DATA-01)
 */
public record AuditEvent(String actor, String action, String resource, Outcome outcome, Instant at) {
    public enum Outcome { ALLOW, DENY }

    public static AuditEvent allow(String actor, String action, String resource) {
        return new AuditEvent(actor, action, resource, Outcome.ALLOW, Instant.now());
    }
    public static AuditEvent deny(String actor, String action, String resource) {
        return new AuditEvent(actor, action, resource, Outcome.DENY, Instant.now());
    }
}
