package com.vgc.tms.m5admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Audit sink (REQ-M5-03). Interface + logging implementation for PR#2.
 * Post-G3 a JPA-backed implementation writes to the `audit_event` table (append-only);
 * the logging impl remains as a secondary sink. Swappable without touching call sites.
 */
public interface AuditService {

    void record(AuditEvent event);

    /** PR#2 logging sink — exercises the hook now; complete + testable pre-schema. */
    @Service
    class LoggingAuditService implements AuditService {
        private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

        @Override
        public void record(AuditEvent e) {
            // Structured, greppable audit line. No secrets/PII in the payload.
            AUDIT.info("audit actor={} action={} resource={} outcome={} at={}",
                    e.actor(), e.action(), e.resource(), e.outcome(), e.at());
        }
    }
}
