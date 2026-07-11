package com.vgc.tms.m5admin;

import org.springframework.context.event.EventListener;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Audits every authorization denial (REQ-M5-03; TS-B-06 "all denials logged").
 * Spring Security publishes AuthorizationDeniedEvent on each deny — we record it, so a
 * Tester/Viewer attempting an admin op leaves an audit trail (not just an HTTP 403).
 */
@Component
public class AuthorizationDeniedAuditListener {

    private final AuditService audit;

    public AuthorizationDeniedAuditListener(AuditService audit) {
        this.audit = audit;
    }

    @EventListener
    public void onDenied(AuthorizationDeniedEvent<?> event) {
        Supplier<Authentication> auth = event.getAuthentication();
        String actor = (auth != null && auth.get() != null) ? auth.get().getName() : "anonymous";
        audit.record(AuditEvent.deny(actor, "ACCESS_DENIED", String.valueOf(event.getObject())));
    }
}
