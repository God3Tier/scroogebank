package bank.alert.api;

import bank.alert.domain.AlertDecisionType;
import bank.alert.event.AgentEventActionService;
import bank.alert.event.AgentEventController;
import bank.alert.event.AgentEventModels.ActionResult;
import bank.alert.event.AgentEventModels.EventPage;
import bank.alert.event.AgentEventQueryService;
import bank.alert.security.JwtRoleConverter;
import bank.alert.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentEventController.class)
@Import({SecurityConfiguration.class, JwtRoleConverter.class, AlertApiExceptionHandler.class})
class AgentEventControllerTest {

    private static final UUID EVENT = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID ALERT = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final String BODY = "{\"decision\":\"DISMISS\",\"members\":[{\"alertId\":\"" + ALERT
            + "\",\"expectedVersion\":0}]}";

    @Autowired MockMvc mockMvc;
    @MockBean AgentEventQueryService queries;
    @MockBean AgentEventActionService actions;
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void listsOnlyForTheAuthenticatedAgent() throws Exception {
        when(queries.list("agent-a", true, false, null, 25)).thenReturn(new EventPage(List.of(), null));

        mockMvc.perform(get("/api/alerts/events").param("onlyOpen", "true").with(user("agent-a").roles("AGENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        when(queries.list("agent-a", false, false, "opaque-page", 25)).thenReturn(new EventPage(List.of(), null));
        mockMvc.perform(get("/api/alerts/events").param("cursor", "opaque-page")
                        .with(user("agent-a").roles("AGENT"))).andExpect(status().isOk());
        verify(queries).list("agent-a", false, false, "opaque-page", 25);
        mockMvc.perform(get("/api/alerts/events").with(user("admin-1").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/alerts/events")).andExpect(status().isUnauthorized());
    }

    @Test
    void anActionNeedsAnExplicitClientSetAndAnIdempotencyKey() throws Exception {
        when(actions.apply(eq(EVENT), eq("agent-a"), eq("action-key-0001"), any(), any()))
                .thenReturn(new ActionResult(UUID.randomUUID(), EVENT, AlertDecisionType.DISMISS,
                        Instant.parse("2026-10-30T02:00:00Z"), List.of()));
        var agent = user("agent-a").roles("AGENT");

        mockMvc.perform(post("/api/alerts/events/{id}/actions", EVENT).with(agent)
                        .header("Idempotency-Key", "action-key-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/alerts/events/{id}/actions", EVENT).with(agent)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/alerts/events/{id}/actions", EVENT).with(agent)
                        .header("Idempotency-Key", "action-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DISMISS\",\"members\":[]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/alerts/events/{id}/actions", EVENT).with(user("admin-1").roles("ADMIN"))
                        .header("Idempotency-Key", "action-key-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        verify(actions).apply(eq(EVENT), eq("agent-a"), eq("action-key-0001"), any(), any());
        verify(actions, never()).apply(any(), eq("admin-1"), any(), any(), any());
    }
}
