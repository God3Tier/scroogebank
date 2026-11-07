package bank.alert.api;

import bank.alert.research.SearchOnAddService;
import bank.alert.security.JwtRoleConverter;
import bank.alert.security.SecurityConfiguration;
import bank.alert.service.HoldingsOverview;
import bank.alert.service.SearchScheduleService;
import bank.alert.service.SearchWebsiteService;
import bank.alert.service.SourceDomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
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

/** The websites, who holds what, Search now and the schedule (ADR-0081, ADR-0082), against the OpenAPI contract. */
@WebMvcTest({SearchWebsiteController.class, SearchHoldingsController.class, SearchNowController.class,
        SearchScheduleController.class})
@Import({SecurityConfiguration.class, JwtRoleConverter.class, AlertApiExceptionHandler.class})
class SearchSourcesControllersTest {

    private static final UUID SITE_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final Instant AT = Instant.parse("2026-11-04T04:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SearchWebsiteService websites;

    @MockBean
    private HoldingsOverview holdings;

    @MockBean
    private SearchOnAddService searchOnAdd;

    @MockBean
    private SearchScheduleService schedule;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static SearchOnAddService.Result started(String... ids) {
        return new SearchOnAddService.Result(SearchOnAddService.Outcome.STARTED, List.of(ids), List.of(), 14);
    }

    // ---- websites ----------------------------------------------------------------------------

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/alerts/search-websites")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/alerts/search-holdings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/alerts/search-schedule")).andExpect(status().isUnauthorized());
    }

    @Test
    void listsTheSharedAndAddedWebsites() throws Exception {
        when(websites.list("agent-a", false)).thenReturn(new SearchWebsiteService.WebsiteList(
                List.of("reuters.com"), List.of(new SearchWebsiteService.Website(SITE_ID, "example.com", AT, true)), true, 20));

        mockMvc.perform(get("/api/alerts/search-websites").with(user("agent-a").roles("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shared[0]").value("reuters.com"))
                .andExpect(jsonPath("$.added[0].domain").value("example.com"))
                .andExpect(jsonPath("$.added[0].addedByYou").value(true))
                .andExpect(jsonPath("$.canAdd").value(true))
                .andExpect(jsonPath("$.max").value(20));
    }

    @Test
    void anAgentAddsAWebsiteAndLearnsWhatHappenedToTheSearch() throws Exception {
        when(websites.add(eq("agent-a"), any(), eq("example.com")))
                .thenReturn(new SearchWebsiteService.Website(SITE_ID, "example.com", AT, true));
        when(holdings.instrumentIdsFor("agent-a")).thenReturn(List.of("SGX-D05", "SGX-O39"));
        when(searchOnAdd.afterAdd(eq("example.com"), eq(List.of("SGX-D05", "SGX-O39")), eq("agent-a"), any()))
                .thenReturn(started("SGX-D05", "SGX-O39"));

        mockMvc.perform(post("/api/alerts/search-websites").with(user("agent-a").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"domain\":\"example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SITE_ID.toString()))
                .andExpect(jsonPath("$.domain").value("example.com"))
                .andExpect(jsonPath("$.search.outcome").value("STARTED"))
                .andExpect(jsonPath("$.search.started[1]").value("SGX-O39"))
                .andExpect(jsonPath("$.search.lookbackDays").value(14));
    }

    @Test
    void anAdminCannotAdd() throws Exception {
        mockMvc.perform(post("/api/alerts/search-websites").with(user("admin-1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"domain\":\"example.com\"}"))
                .andExpect(status().isForbidden());
        verify(websites, never()).add(any(), any(), any());
    }

    @Test
    void aBlankWebsiteIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/alerts/search-websites").with(user("agent-a").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"domain\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusalsCarryTheirStatusAndStableCodeAndNeverStartASearch() throws Exception {
        when(websites.add(any(), any(), any()))
                .thenThrow(SourceDomainException.invalid("BLOCKED_DOMAIN", "social platforms are not news sources"))
                .thenThrow(SourceDomainException.conflict("LIMIT_REACHED", "too many"));

        for (int expected : new int[] {400, 409}) {
            mockMvc.perform(post("/api/alerts/search-websites").with(user("agent-a").roles("AGENT"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"domain\":\"reddit.com\"}"))
                    .andExpect(status().is(expected))
                    .andExpect(jsonPath("$.code").exists());
        }
        verify(searchOnAdd, never()).afterAdd(any(), any(), any(), any());
    }

    @Test
    void anyAgentAndAnAdminCanRemoveAWebsite() throws Exception {
        mockMvc.perform(delete("/api/alerts/search-websites/{id}", SITE_ID).with(user("agent-a").roles("AGENT")))
                .andExpect(status().isNoContent());
        verify(websites).remove(eq(SITE_ID), eq("agent-a"), eq(false), any());

        mockMvc.perform(delete("/api/alerts/search-websites/{id}", SITE_ID).with(user("admin-1").roles("ADMIN")))
                .andExpect(status().isNoContent());
        verify(websites).remove(eq(SITE_ID), eq("admin-1"), eq(true), any());
    }

    // ---- who holds what ----------------------------------------------------------------------

    @Test
    void listsWhoHoldsEachCompany() throws Exception {
        when(holdings.forCaller("agent-a", false)).thenReturn(List.of(new HoldingsOverview.CompanyHoldings(
                "SGX-D05", "D05", "DBS Group Holdings Ltd", List.of("dbs.com"), List.of(new HoldingsOverview.Holder(
                        "c0000000-0000-4000-8000-000000000001", "a0000000-0000-4000-8000-000000000001",
                        new BigDecimal("1000"), new BigDecimal("36000"), new BigDecimal("76.3"), null)))));

        mockMvc.perform(get("/api/alerts/search-holdings").with(user("agent-a").roles("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("D05"))
                .andExpect(jsonPath("$[0].holders[0].sharePercent").value(76.3))
                .andExpect(jsonPath("$[0].holders[0].quantity").value(1000));
    }

    @Test
    void anAdminListsEveryoneWithTheOwningAgent() throws Exception {
        when(holdings.forCaller("admin-1", true)).thenReturn(List.of(new HoldingsOverview.CompanyHoldings(
                "SGX-D05", "D05", "DBS", List.of(), List.of(new HoldingsOverview.Holder(
                        "c0000000-0000-4000-8000-000000000001", "a0000000-0000-4000-8000-000000000001",
                        new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("100"), "agent-a")))));

        mockMvc.perform(get("/api/alerts/search-holdings").with(user("admin-1").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].holders[0].ownerAgentSubject").value("agent-a"));
    }

    // ---- Search now --------------------------------------------------------------------------

    @Test
    void anAgentSearchesEveryCompanyTheirClientsHold() throws Exception {
        when(holdings.instrumentIdsFor("agent-a")).thenReturn(List.of("SGX-D05", "SGX-O39"));
        when(searchOnAdd.manual(eq(List.of("SGX-D05", "SGX-O39")), eq("agent-a"), any())).thenReturn(started("SGX-D05", "SGX-O39"));

        mockMvc.perform(post("/api/alerts/search-now").with(user("agent-a").roles("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("STARTED"))
                .andExpect(jsonPath("$.started.length()").value(2));
    }

    @Test
    void anAgentCanSearchOneCompanyTheirClientsHold() throws Exception {
        when(holdings.instrumentIdsFor("agent-a")).thenReturn(List.of("SGX-D05", "SGX-O39"));
        when(searchOnAdd.manual(eq(List.of("SGX-D05")), eq("agent-a"), any())).thenReturn(new SearchOnAddService.Result(
                SearchOnAddService.Outcome.COOLDOWN, List.of(),
                List.of(new SearchOnAddService.Skipped("SGX-D05", AT.plusSeconds(600))), 14));

        mockMvc.perform(post("/api/alerts/search-now").with(user("agent-a").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":\"SGX-D05\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("COOLDOWN"))
                .andExpect(jsonPath("$.cooldown[0].instrumentId").value("SGX-D05"))
                .andExpect(jsonPath("$.cooldown[0].availableAt").exists());
    }

    @Test
    void anAgentCannotSearchACompanyTheirClientsDoNotHold() throws Exception {
        when(holdings.instrumentIdsFor("agent-a")).thenReturn(List.of("SGX-D05"));

        mockMvc.perform(post("/api/alerts/search-now").with(user("agent-a").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":\"NYSE-DIS\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_HELD"));
        verify(searchOnAdd, never()).manual(any(), any(), any());
    }

    @Test
    void anAdminSearchesAnyCompanyAnyoneHolds() throws Exception {
        when(holdings.allHeldInstrumentIds()).thenReturn(List.of("SGX-D05", "NYSE-DIS"));
        when(searchOnAdd.manual(eq(List.of("SGX-D05", "NYSE-DIS")), eq("admin-1"), any())).thenReturn(started("SGX-D05", "NYSE-DIS"));

        mockMvc.perform(post("/api/alerts/search-now").with(user("admin-1").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started.length()").value(2));
    }

    @Test
    void anAdminAskingForACompanyNobodyHoldsIsNotFound() throws Exception {
        when(holdings.allHeldInstrumentIds()).thenReturn(List.of("SGX-D05"));

        mockMvc.perform(post("/api/alerts/search-now").with(user("admin-1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":\"NYSE-DIS\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- schedule ----------------------------------------------------------------------------

    @Test
    void showsWhenTheNextAutomaticSearchIs() throws Exception {
        when(schedule.current()).thenReturn(new SearchScheduleService.Schedule(true, 240, "Asia/Singapore",
                Instant.parse("2026-11-04T00:00:00Z"), Instant.parse("2026-11-04T04:00:00Z")));

        mockMvc.perform(get("/api/alerts/search-schedule").with(user("agent-a").roles("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalMinutes").value(240))
                .andExpect(jsonPath("$.zone").value("Asia/Singapore"))
                .andExpect(jsonPath("$.nextRunAt").value("2026-11-04T04:00:00Z"));
    }
}
