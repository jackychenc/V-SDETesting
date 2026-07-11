package com.vgc.tms.config;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * RBAC deny-by-default — server-side central filter (REQ-M5-01, AC [REQ-M5-01-AC1]).
 * Implements C4 threat-model mitigation M5-T2 (deny-by-default, central, server-side).
 * Four roles from B1 (Viewer, Tester, Test Lead, Administrator); least-privilege.
 *
 * PR#2 (feat/m5-rbac-skeleton): endpoint→role authorization matrix + method-security.
 * Persisted User/Role/ProjectRoleAssignment store depends on B2's data model → post-G3;
 * pilot uses the SSO/LDAP stub (REQ-M5-02, OUT-01) for authentication + group→role.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // enables @PreAuthorize on mutating service ops (REQ-M5-03 audit + method RBAC)
public class SecurityConfig {

    /** Roles (B1). Spring authorities use the ROLE_ prefix. */
    public enum Role { VIEWER, TESTER, TEST_LEAD, ADMINISTRATOR }

    /**
     * Publishes AuthorizationDeniedEvent on every denial so {@link com.vgc.tms.m5admin.AuthorizationDeniedAuditListener}
     * records the DENY audit (REQ-M5-03). Without this bean, Spring Security does NOT emit deny events →
     * denials would 403 but never be audited (the real bug behind the RBAC negative-audit gap).
     */
    @Bean
    public AuthorizationEventPublisher authorizationEventPublisher(ApplicationEventPublisher publisher) {
        return new SpringAuthorizationEventPublisher(publisher);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())                 // stateless token API via BFF; TLS at ingress (NFR-SEC-01)
            .authorizeHttpRequests(auth -> auth
                // --- open: K8s probes only (NFR-AVAIL-01) ---
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()

                // --- ADMINISTRATOR: sync-config, RBAC/admin, audit (REQ-M5-01/03/04, M1-01) ---
                .requestMatchers("/api/sync/config/**", "/api/admin/**", "/api/audit/**")
                    .hasRole(Role.ADMINISTRATOR.name())

                // --- TEST_LEAD: plan/close runs, assign, resolve sync conflicts (REQ-M2-01/04, M1-04) ---
                .requestMatchers(HttpMethod.POST, "/api/runs/**").hasAnyRole(Role.TEST_LEAD.name(), Role.ADMINISTRATOR.name())
                .requestMatchers(HttpMethod.PUT, "/api/runs/**").hasAnyRole(Role.TEST_LEAD.name(), Role.ADMINISTRATOR.name())
                .requestMatchers(HttpMethod.POST, "/api/sync/conflicts/**").hasAnyRole(Role.TEST_LEAD.name(), Role.ADMINISTRATOR.name())

                // --- TESTER: record executions, create defects (REQ-M2-02, M3-01) ---
                .requestMatchers(HttpMethod.POST, "/api/executions/**", "/api/defects/**")
                    .hasAnyRole(Role.TESTER.name(), Role.TEST_LEAD.name(), Role.ADMINISTRATOR.name())

                // --- VIEWER (and all authenticated): read-only dashboards/reports (REQ-M4-*) ---
                .requestMatchers(HttpMethod.GET, "/api/**").authenticated()

                // --- DENY-BY-DEFAULT: anything not explicitly allowed above ---
                .anyRequest().denyAll()
            )
            .httpBasic(basic -> {});                       // pilot SSO/LDAP stub (REQ-M5-02); prod = ISSO IdP
        return http.build();                               // build the SecurityFilterChain (was: returning HttpSecurity)
    }
}
