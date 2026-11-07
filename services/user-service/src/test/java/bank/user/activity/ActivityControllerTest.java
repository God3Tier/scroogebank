package bank.user.activity;

import bank.user.audit.AuditEventPublisher;
import bank.user.exception.AuditorUnavailableException;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivityController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class})
class ActivityControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ActivityService activityService;

    // UserAuditInterceptor, which @WebMvcTest loads, publishes through this.
    @MockitoBean
    private AuditEventPublisher auditEventPublisher;

    // SecurityConfig's decoder fetches the issuer's OIDC metadata when created.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    // jwt() signs the request in with a token whose value is "token"; the
    // controller forwards it to the auditor.
    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-sub")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor agent() {
        return jwt().jwt(token -> token.subject("agent-sub")).authorities(new SimpleGrantedAuthority("ROLE_AGENT"));
    }

    @Test
    void adminGetsAnAgentsActivityInAWindow() throws Exception {
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-30T00:00:00Z");
        ActivityQuery query = new ActivityQuery("agent-sub-7", null, from, to, 10, "42");
        when(activityService.list(query, "token")).thenReturn(new ActivityPage(List.of(new ActivityResponse(
                "entry-41", "CRM", "UPDATE", "agent-sub-7", "Alice Tan", from, null)), "41"));

        mvc.perform(get("/api/activities")
                        .param("agentId", "agent-sub-7")
                        .param("from", "2026-09-01T00:00:00Z").param("to", "2026-09-30T00:00:00Z")
                        .param("size", "10").param("cursor", "42")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.content[0].actorId").value("agent-sub-7"))
                .andExpect(jsonPath("$.content[0].actorName").value("Alice Tan"))
                .andExpect(jsonPath("$.nextCursor").value("41"));
    }

    @Test
    void adminWithNoFiltersGetsEveryAgentsNewestTwenty() throws Exception {
        when(activityService.list(any(), any())).thenReturn(new ActivityPage(List.of(), null));

        mvc.perform(get("/api/activities").with(admin()))
                .andExpect(status().isOk());

        verify(activityService).list(new ActivityQuery(null, null, null, null, 20, null), "token");
    }

    @Test
    void agentGetsOnlyTheirOwnActivities() throws Exception {
        when(activityService.list(any(), any())).thenReturn(new ActivityPage(List.of(), null));

        mvc.perform(get("/api/activities").with(agent()))
                .andExpect(status().isOk());

        verify(activityService).list(new ActivityQuery("agent-sub", null, null, null, 20, null), "token");
    }

    @Test
    void agentMayNameThemselves() throws Exception {
        when(activityService.list(any(), any())).thenReturn(new ActivityPage(List.of(), null));

        mvc.perform(get("/api/activities").param("agentId", "agent-sub").with(agent()))
                .andExpect(status().isOk());

        verify(activityService).list(new ActivityQuery("agent-sub", null, null, null, 20, null), "token");
    }

    @Test
    void agentCanFilterTheirActivityByClientAndTimeWindow() throws Exception {
        when(activityService.list(any(), any())).thenReturn(new ActivityPage(List.of(), null));

        mvc.perform(get("/api/activities")
                        .param("clientId", "00000000-0000-0000-0000-000000000123")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-30T00:00:00Z")
                        .with(agent()))
                .andExpect(status().isOk());

        verify(activityService).list(new ActivityQuery("agent-sub", "00000000-0000-0000-0000-000000000123",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"), 20, null), "token");
    }

    @Test
    void invalidClientIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/activities").param("clientId", "not-a-uuid").with(agent()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("clientId: must be a UUID"));
        verifyNoInteractions(activityService);
    }

    @Test
    void agentAskingForAnotherAgentIsForbidden() throws Exception {
        mvc.perform(get("/api/activities").param("agentId", "agent-sub-7").with(agent()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Agents can see only their own activities"));
        verifyNoInteractions(activityService);
    }

    @Test
    void userInNoCrmGroupIsForbidden() throws Exception {
        mvc.perform(get("/api/activities").with(jwt()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(activityService);
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(get("/api/activities"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(activityService);
    }

    @Test
    void oversizedPageIsBadRequest() throws Exception {
        mvc.perform(get("/api/activities").param("size", "101").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size: must be between 1 and 100"));
        verifyNoInteractions(activityService);
    }

    @Test
    void reversedTimeRangeIsBadRequest() throws Exception {
        mvc.perform(get("/api/activities")
                        .param("from", "2026-09-30T00:00:00Z").param("to", "2026-09-01T00:00:00Z")
                        .with(admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(activityService);
    }

    @Test
    void unavailableAuditorIsServiceUnavailable() throws Exception {
        when(activityService.list(any(), any()))
                .thenThrow(new AuditorUnavailableException("Activity log is temporarily unavailable"));

        mvc.perform(get("/api/activities").with(agent()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Activity log is temporarily unavailable"));
    }
}
