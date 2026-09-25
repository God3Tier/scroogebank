package bank.user.activity;

import bank.user.exception.AuditorUnavailableException;
import bank.user.security.JwtRoleConverter;
import bank.user.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    @Test
    void adminGetsFilteredActivity() throws Exception {
        UUID agent = UUID.randomUUID();
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-30T00:00:00Z");
        ActivityQuery query = new ActivityQuery(agent, from, to, 10, 42L);
        when(activityService.list(query)).thenReturn(new ActivityPage(List.of(new ActivityResponse(
                "entry-41", 41, "CRM", "CRUD", "UPDATE_CLIENT", agent.toString(), from, null)), 41L));

        mvc.perform(get("/api/activities")
                        .param("agentId", agent.toString())
                        .param("from", "2026-09-01T00:00:00Z").param("to", "2026-09-30T00:00:00Z")
                        .param("size", "10").param("cursor", "42")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("UPDATE_CLIENT"))
                .andExpect(jsonPath("$.content[0].actorId").value(agent.toString()))
                .andExpect(jsonPath("$.nextCursor").value(41));
    }

    @Test
    void defaultsToNewestTwenty() throws Exception {
        when(activityService.list(any())).thenReturn(new ActivityPage(List.of(), null));

        mvc.perform(get("/api/activities").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        verify(activityService).list(new ActivityQuery(null, null, null, 20, null));
    }

    @Test
    void agentIsForbidden() throws Exception {
        mvc.perform(get("/api/activities").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AGENT"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(activityService);
    }

    @Test
    void oversizedPageIsBadRequest() throws Exception {
        mvc.perform(get("/api/activities").param("size", "101")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size: must be between 1 and 100"));
        verifyNoInteractions(activityService);
    }

    @Test
    void reversedTimeRangeIsBadRequest() throws Exception {
        mvc.perform(get("/api/activities")
                        .param("from", "2026-09-30T00:00:00Z").param("to", "2026-09-01T00:00:00Z")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(activityService);
    }

    @Test
    void unavailableAuditorIsServiceUnavailable() throws Exception {
        when(activityService.list(any()))
                .thenThrow(new AuditorUnavailableException("Activity log is temporarily unavailable"));

        mvc.perform(get("/api/activities").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Activity log is temporarily unavailable"));
    }
}
