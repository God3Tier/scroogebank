package bank.alert.api;

import bank.alert.domain.AlertDecision;
import bank.alert.domain.AlertDecisionType;
import bank.alert.domain.AlertFeedback;
import bank.alert.domain.AlertStatus;
import bank.alert.domain.FeedbackRating;
import bank.alert.security.JwtRoleConverter;
import bank.alert.security.SecurityConfiguration;
import bank.alert.service.AlertMutationService;
import bank.alert.service.AuditUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertMutationController.class)
@Import({SecurityConfiguration.class, JwtRoleConverter.class, AlertApiExceptionHandler.class})
class AlertMutationControllerTest {

    private static final UUID ALERT_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-27T00:10:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AlertMutationService mutationService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void assignedAgentIdentityComesFromAuthentication() throws Exception {
        AlertDecision decision = new AlertDecision(
                UUID.randomUUID(), ALERT_ID, "syn-agent-001", AlertDecisionType.MONITOR,
                AlertStatus.NEW, AlertStatus.MONITORING, null, "decision-key-001", NOW, 1L);
        when(mutationService.recordDecision(
                eq(ALERT_ID), eq("syn-agent-001"), eq("decision-key-001"), any(), any()))
                .thenReturn(decision);

        mockMvc.perform(post("/api/alerts/{alertId}/decisions", ALERT_ID)
                        .with(user("syn-agent-001").roles("AGENT"))
                        .header("Idempotency-Key", "decision-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"MONITOR","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorSubject").value("syn-agent-001"))
                .andExpect(jsonPath("$.alertVersion").value(1));

        verify(mutationService).recordDecision(
                eq(ALERT_ID), eq("syn-agent-001"), eq("decision-key-001"), any(), any());
    }

    @Test
    void adminIsReadOnlyAndAnonymousIsUnauthorized() throws Exception {
        String request = "{\"decision\":\"MONITOR\",\"expectedVersion\":0}";

        mockMvc.perform(post("/api/alerts/{alertId}/decisions", ALERT_ID)
                        .with(user("syn-admin-001").roles("ADMIN"))
                        .header("Idempotency-Key", "decision-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.correlationId").exists());

        mockMvc.perform(post("/api/alerts/{alertId}/decisions", ALERT_ID)
                        .header("Idempotency-Key", "decision-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void validatesIdempotencyKeyAndRequestBody() throws Exception {
        mockMvc.perform(post("/api/alerts/{alertId}/decisions", ALERT_ID)
                        .with(user("syn-agent-001").roles("AGENT"))
                        .header("Idempotency-Key", "short")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void recordsFeedbackForAgent() throws Exception {
        AlertFeedback feedback = new AlertFeedback(
                UUID.randomUUID(), ALERT_ID, "syn-agent-001", FeedbackRating.USEFUL,
                null, null, "feedback-key-001", NOW, 0L);
        when(mutationService.recordFeedback(
                eq(ALERT_ID), eq("syn-agent-001"), eq("feedback-key-001"), any(), any()))
                .thenReturn(feedback);

        mockMvc.perform(post("/api/alerts/{alertId}/feedback", ALERT_ID)
                        .with(user("syn-agent-001").roles("AGENT"))
                        .header("Idempotency-Key", "feedback-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":"USEFUL","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("USEFUL"));
    }

    @Test
    void returnsRetryableProblemWhenAuditPersistenceIsUnavailable() throws Exception {
        when(mutationService.recordDecision(
                eq(ALERT_ID), eq("syn-agent-001"), eq("decision-key-001"), any(), any()))
                .thenThrow(new AuditUnavailableException("audit outbox is at capacity"));

        mockMvc.perform(post("/api/alerts/{alertId}/decisions", ALERT_ID)
                        .with(user("syn-agent-001").roles("AGENT"))
                        .header("Idempotency-Key", "decision-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"MONITOR","expectedVersion":0}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.title").value("Audit persistence unavailable"))
                .andExpect(jsonPath("$.correlationId").exists());
    }
}
