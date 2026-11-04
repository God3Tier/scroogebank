package bank.alert.api;

import bank.alert.security.JwtRoleConverter;
import bank.alert.security.SecurityConfiguration;
import bank.alert.service.SearchSourceService;
import bank.alert.service.SourceDomainException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchSourceController.class)
@Import({SecurityConfiguration.class, JwtRoleConverter.class, AlertApiExceptionHandler.class})
class SearchSourceControllerTest {

    private static final UUID DOMAIN_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SearchSourceService service;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/alerts/search-sources")).andExpect(status().isUnauthorized());
    }

    @Test
    void listsForAnAgentUsingTheTokenIdentity() throws Exception {
        when(service.list("agent-a", false)).thenReturn(List.of(new SearchSourceService.CompanySources(
                "SGX-D05", "D05", "DBS Group Holdings Ltd", List.of("sgx.com"), List.of("reuters.com"),
                List.of(new SearchSourceService.CustomDomain(DOMAIN_ID, "example.com", Instant.parse("2026-11-04T04:00:00Z"), true)),
                true, 10)));

        mockMvc.perform(get("/api/alerts/search-sources").with(user("agent-a").roles("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].instrumentId").value("SGX-D05"))
                .andExpect(jsonPath("$[0].custom[0].domain").value("example.com"))
                .andExpect(jsonPath("$[0].custom[0].addedByYou").value(true))
                .andExpect(jsonPath("$[0].canAdd").value(true));
    }

    @Test
    void anAdminListsAsAnAdmin() throws Exception {
        when(service.list("admin-1", true)).thenReturn(List.of());

        mockMvc.perform(get("/api/alerts/search-sources").with(user("admin-1").roles("ADMIN")))
                .andExpect(status().isOk());
        verify(service).list("admin-1", true);
    }

    @Test
    void anAgentAddsADomainAndGetsCreated() throws Exception {
        when(service.add(eq("SGX-D05"), eq("agent-a"), any(), eq("example.com")))
                .thenReturn(new SearchSourceService.CustomDomain(DOMAIN_ID, "example.com", Instant.parse("2026-11-04T04:00:00Z"), true));

        mockMvc.perform(post("/api/alerts/search-sources/SGX-D05/domains").with(user("agent-a").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"domain\":\"example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(DOMAIN_ID.toString()))
                .andExpect(jsonPath("$.domain").value("example.com"));
    }

    @Test
    void anAdminCannotAdd() throws Exception {
        mockMvc.perform(post("/api/alerts/search-sources/SGX-D05/domains").with(user("admin-1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"domain\":\"example.com\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).add(any(), any(), any(), any());
    }

    @Test
    void aBlankDomainIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/alerts/search-sources/SGX-D05/domains").with(user("agent-a").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"domain\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusalsCarryTheirStatusAndStableCode() throws Exception {
        when(service.add(any(), any(), any(), any()))
                .thenThrow(SourceDomainException.invalid("BLOCKED_DOMAIN", "social platforms are not news sources"))
                .thenThrow(SourceDomainException.forbidden())
                .thenThrow(SourceDomainException.conflict("LIMIT_REACHED", "too many"));

        for (int expected : new int[] {400, 403, 409}) {
            mockMvc.perform(post("/api/alerts/search-sources/SGX-D05/domains").with(user("agent-a").roles("AGENT"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"domain\":\"reddit.com\"}"))
                    .andExpect(status().is(expected))
                    .andExpect(jsonPath("$.code").exists());
        }
    }

    @Test
    void agentsAndAdminsCanDeactivate() throws Exception {
        mockMvc.perform(delete("/api/alerts/search-sources/SGX-D05/domains/{id}", DOMAIN_ID)
                        .with(user("agent-a").roles("AGENT")))
                .andExpect(status().isNoContent());
        verify(service).deactivate(eq("SGX-D05"), eq(DOMAIN_ID), eq("agent-a"), eq(false), any());

        mockMvc.perform(delete("/api/alerts/search-sources/SGX-D05/domains/{id}", DOMAIN_ID)
                        .with(user("admin-1").roles("ADMIN")))
                .andExpect(status().isNoContent());
        verify(service).deactivate(eq("SGX-D05"), eq(DOMAIN_ID), eq("admin-1"), eq(true), any());
    }
}
