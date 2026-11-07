package bank.user.activity;

import bank.user.audit.AuditEventPublisher;
import bank.user.security.JwtRoleConverter;
import bank.user.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.time.Instant;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditLogController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class})
class AuditLogControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ActivityService activityService;
    @MockitoBean private AuditEventPublisher auditEventPublisher;
    @MockitoBean private JwtDecoder jwtDecoder;

    @Test
    void adminCanListEveryAuditEvent() throws Exception {
        when(activityService.listAuditLogs(20, "21", "token"))
                .thenReturn(new AuditLogPage(List.of(new AuditLogResponse(
                        "entry-20", "CRUD", "TRANSACTION_SERVICE", "READ",
                        "admin-sub", "Admin Example", Instant.parse("2026-11-07T04:00:00Z"),
                        JsonNodeFactory.instance.objectNode())), "20"));

        mvc.perform(get("/api/audit-logs").param("cursor", "21")
                        .with(jwt().jwt(token -> token.subject("admin-sub"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sourceService").value("TRANSACTION_SERVICE"))
                .andExpect(jsonPath("$.content[0].eventType").value("CRUD"))
                .andExpect(jsonPath("$.content[0].action").value("READ"));

        verify(activityService).listAuditLogs(20, "21", "token");
    }

    @Test
    void agentCannotListTheCompleteAuditLog() throws Exception {
        mvc.perform(get("/api/audit-logs")
                        .with(jwt().jwt(token -> token.subject("agent-sub"))
                                .authorities(new SimpleGrantedAuthority("ROLE_AGENT"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(activityService);
    }

    @Test
    void invalidPageSizeIsRejectedBeforeCallingAuditor() throws Exception {
        mvc.perform(get("/api/audit-logs").param("size", "101")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(activityService);
    }
}
