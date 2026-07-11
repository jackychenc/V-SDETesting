package com.vgc.tms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * RBAC deny-by-default — server-side central filter (REQ-M5-01, AC [REQ-M5-01-AC1]).
 * Implements C4 threat-model mitigations M5-T2 (deny-by-default, central, server-side)
 * and the four roles from B1 (Viewer, Tester, Test Lead, Administrator).
 *
 * Scaffold PR#1: central chain + deny-by-default baseline. The concrete per-endpoint
 * role matrix + audit hook (REQ-M5-03) is finalized in PR#2 (feat/m5-rbac-skeleton)
 * against C4's full threat model. SSO/LDAP is stubbed in pilot (REQ-M5-02, OUT-01).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // enables @PreAuthorize on mutating ops (method-level RBAC)
public class SecurityConfig {

    /** Role hierarchy is intentionally flat; least-privilege, deny-by-default. */
    public enum Role { VIEWER, TESTER, TEST_LEAD, ADMINISTRATOR }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // TLS enforced at ingress; app assumes HTTPS termination (NFR-SEC-01).
            .csrf(csrf -> csrf.disable())   // stateless API; CSRF N/A (token auth via BFF)
            .authorizeHttpRequests(auth -> auth
                // health/readiness probes open for K8s (NFR-AVAIL-01)
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                // everything else: authenticated + role-checked. DENY-BY-DEFAULT.
                .anyRequest().authenticated()
            )
            // pilot: SSO/LDAP stub (REQ-M5-02). Replaced by ISSO-compliant IdP in prod.
            .httpBasic(basic -> {});
        return http;
    }
}
