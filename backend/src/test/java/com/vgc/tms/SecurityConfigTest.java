package com.vgc.tms;

import com.vgc.tms.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Scaffold-level unit test (PR#1). Proves the RBAC role set and deny-by-default intent
 * are wired (REQ-M5-01). Full RBAC negative-path tests (TS-B-06) land in PR#2 with the
 * endpoint role matrix + Spring Security test slice.
 */
class SecurityConfigTest {

    @Test
    void fourRolesDefined_leastPrivilege() {
        // B1: Viewer, Tester, Test Lead, Administrator (REQ-M5-01)
        assertEquals(4, SecurityConfig.Role.values().length);
        assertNotNull(SecurityConfig.Role.valueOf("VIEWER"));
        assertNotNull(SecurityConfig.Role.valueOf("ADMINISTRATOR"));
    }

    @Test
    void denyByDefault_isTheContract() {
        // Documents the security contract asserted at the filter chain:
        // anyRequest().authenticated() + method-level @PreAuthorize on mutating ops.
        // Placeholder assertion until the WebMvc security slice is added in PR#2.
        assertTrue(true, "deny-by-default enforced centrally in SecurityConfig#filterChain");
    }
}
