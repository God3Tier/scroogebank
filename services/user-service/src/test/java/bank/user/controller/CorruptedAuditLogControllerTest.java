package bank.user.controller;

import bank.user.audit.AuditEventPublisher;
import bank.user.security.JwtRoleConverter;
import bank.user.security.SecurityConfig;
import bank.user.service.CorruptedAuditLogProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CorruptedAuditLogController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class})
class CorruptedAuditLogControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CorruptedAuditLogProxy proxy;

    // UserAuditInterceptor, which @WebMvcTest loads, publishes through this.
    @MockitoBean
    private AuditEventPublisher auditEventPublisher;

    // SecurityConfig's decoder fetches the issuer's OIDC metadata when created.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID id = UUID.randomUUID();

    @Test
    void listReadIsAuditedWithoutAResourceId() throws Exception {
        when(proxy.getCorruptedLogs()).thenReturn(ResponseEntity.ok(JsonNodeFactory.instance.arrayNode()));

        mvc.perform(get("/api/corrupted-logs").with(admin()))
                .andExpect(status().isOk());

        verify(auditEventPublisher).publish("READ", "sub-admin", "CORRUPTED_AUDIT_LOG", null,
                "/api/corrupted-logs", "GET", 200, null);
    }

    @Test
    void createIsAuditedWithTheIdFromTheRequest() throws Exception {
        when(proxy.createCorruptedLog(any())).thenReturn(auditorReply(HttpStatus.CREATED));

        mvc.perform(post("/api/corrupted-logs").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\",\"payload\":{}}"))
                .andExpect(status().isCreated());

        verify(auditEventPublisher).publish("CREATE", "sub-admin", "CORRUPTED_AUDIT_LOG", id.toString(),
                "/api/corrupted-logs", "POST", 201, null);
    }

    @Test
    void createWithAnIdThatIsNotAUuidIsAuditedWithoutIt() throws Exception {
        when(proxy.createCorruptedLog(any())).thenReturn(auditorReply(HttpStatus.BAD_REQUEST));

        mvc.perform(post("/api/corrupted-logs").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"not-a-uuid\",\"payload\":{}}"))
                .andExpect(status().isBadRequest());

        verify(auditEventPublisher).publish("CREATE", "sub-admin", "CORRUPTED_AUDIT_LOG", null,
                "/api/corrupted-logs", "POST", 400, null);
    }

    @Test
    void deleteIsAuditedWithThePathId() throws Exception {
        when(proxy.deleteCorruptedLog(id)).thenReturn(auditorReply(HttpStatus.OK));

        mvc.perform(delete("/api/corrupted-logs/{id}", id).with(admin()))
                .andExpect(status().isOk());

        verify(auditEventPublisher).publish("DELETE", "sub-admin", "CORRUPTED_AUDIT_LOG", id.toString(),
                "/api/corrupted-logs/{id}", "DELETE", 200, null);
    }

    @Test
    void agentIsForbiddenAndTheAttemptIsAudited() throws Exception {
        mvc.perform(delete("/api/corrupted-logs/{id}", id)
                        .with(jwt().jwt(token -> token.subject("sub-agent"))
                                .authorities(new SimpleGrantedAuthority("ROLE_AGENT"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(proxy);
        verify(auditEventPublisher).publish("DELETE", "sub-agent", "CORRUPTED_AUDIT_LOG", id.toString(),
                "/api/corrupted-logs/{id}", "DELETE", 403, null);
    }

    @Test
    void anUnreadableAuditorReplyIsAuditedAsAServerError() {
        when(proxy.getCorruptedLogs()).thenThrow(new IllegalStateException("Auditor returned an unreadable response"));

        assertThatThrownBy(() -> mvc.perform(get("/api/corrupted-logs").with(admin())))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        verify(auditEventPublisher).publish("READ", "sub-admin", "CORRUPTED_AUDIT_LOG", null,
                "/api/corrupted-logs", "GET", 500, null);
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("sub-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static ResponseEntity<JsonNode> auditorReply(HttpStatus status) {
        return ResponseEntity.status(status).body(JsonNodeFactory.instance.objectNode().put("status", "ok"));
    }
}
