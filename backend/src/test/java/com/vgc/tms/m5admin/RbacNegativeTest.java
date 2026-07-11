package com.vgc.tms.m5admin;

import com.vgc.tms.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC deny-by-default negative matrix (REQ-M5-01, AC [REQ-M5-01-AC1]; maps TS-B-06).
 * Proves: only ADMINISTRATOR may PUT sync-config; Tester/Viewer are denied; anonymous unauthorized;
 * and every denial is audit-logged (REQ-M5-03).
 */
@WebMvcTest(SyncConfigController.class)
// Import the deny-audit listener into the slice (a @Component; not auto-loaded by @WebMvcTest) so the
// AuthorizationDeniedEvent published by SecurityConfig's AuthorizationEventPublisher records the DENY audit.
@Import({SecurityConfig.class, AuthorizationDeniedAuditListener.class})
class RbacNegativeTest {

    @Autowired MockMvc mvc;
    @MockBean AuditService audit;

    private static final String BODY = "{\"schedule\":\"PT15M\"}";

    @Test @WithMockUser(username = "admin", roles = "ADMINISTRATOR")
    void administrator_canUpdateSyncConfig() throws Exception {
        mvc.perform(put("/api/sync/config/PROJ1").with(csrf())
                .contentType("application/json").content(BODY))
           .andExpect(status().isOk());
        verify(audit).record(argThat(e -> e.action().equals("SYNC_CONFIG_UPDATE")
                && e.outcome() == AuditEvent.Outcome.ALLOW));
    }

    @Test @WithMockUser(username = "tester", roles = "TESTER")
    void tester_isDenied_andAudited() throws Exception {
        mvc.perform(put("/api/sync/config/PROJ1").with(csrf())
                .contentType("application/json").content(BODY))
           .andExpect(status().isForbidden());
        verify(audit, atLeastOnce()).record(argThat(e -> e.outcome() == AuditEvent.Outcome.DENY));
    }

    @Test @WithMockUser(username = "viewer", roles = "VIEWER")
    void viewer_isDenied() throws Exception {
        mvc.perform(put("/api/sync/config/PROJ1").with(csrf())
                .contentType("application/json").content(BODY))
           .andExpect(status().isForbidden());
    }

    @Test
    void anonymous_isUnauthorized() throws Exception {
        mvc.perform(put("/api/sync/config/PROJ1").with(csrf())
                .contentType("application/json").content(BODY))
           .andExpect(status().isUnauthorized());
    }
}
