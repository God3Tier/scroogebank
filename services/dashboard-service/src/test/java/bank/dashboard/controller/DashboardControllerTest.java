package bank.dashboard.controller;

import bank.dashboard.service.DashboardCache;
import bank.dashboard.testsupport.DashboardTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end behaviour of the API as it stands: no upstream exists, so every
 * panel is unavailable. These assertions are the specification of that state,
 * not a placeholder -- a panel whose service has not been built takes the same
 * path as one whose service is down.
 */
@DashboardTest
@AutoConfigureMockMvc
class DashboardControllerTest {

    private static final String AGENT_SUB = "11111111-1111-1111-1111-111111111111";
    private static final String ADMIN_SUB = "22222222-2222-2222-2222-222222222222";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DashboardCache cache;

    @BeforeEach
    void clearCache() {
        // Otherwise one test's response is served to the next, and the cache
        // key assertions below would pass for the wrong reason.
        cache.invalidateAll();
    }

    @Test
    void reportsEveryPanelUnavailableAndSaysWhy() throws Exception {
        mockMvc.perform(asAgent(get("/api/dashboard/overview")))
                // 503 because nothing at all resolved. A partial result would
                // be a 200.
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.scope").value("AGENT"))
                .andExpect(jsonPath("$.panels.clients.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.panels.clients.data").doesNotExist())
                .andExpect(jsonPath("$.panels.clients.reason").value("upstream_not_deployed"))
                .andExpect(jsonPath("$.panels.transactions.reason").value("upstream_not_deployed"));
    }

    @Test
    void scopeComesFromTheTokenNotTheRequest() throws Exception {
        mockMvc.perform(asAdmin(get("/api/dashboard/overview")))
                .andExpect(jsonPath("$.scope").value("ADMIN"))
                // Admins get an agent roster panel; agents do not.
                .andExpect(jsonPath("$.panels.agents").exists());

        mockMvc.perform(asAgent(get("/api/dashboard/overview")))
                .andExpect(jsonPath("$.scope").value("AGENT"))
                .andExpect(jsonPath("$.panels.agents").doesNotExist());
    }

    @Test
    void anAgentCannotReachThePerAgentPerformanceTable() throws Exception {
        mockMvc.perform(asAgent(get("/api/dashboard/agents")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminCanReachThePerAgentPerformanceTable() throws Exception {
        mockMvc.perform(asAdmin(get("/api/dashboard/agents")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.panels.agents.status").value("UNAVAILABLE"));
    }

    @Test
    void anAgentCannotWidenScopeWithAForgedAgentIdParameter() throws Exception {
        // There is no agentId parameter to honour. Spring ignores the unknown
        // query parameter, and the response is still AGENT-scoped -- the
        // dashboard passes no agent identifier upstream at all, so there is
        // nothing to forge rather than a check that could be missed.
        mockMvc.perform(asAgent(get("/api/dashboard/overview").param("agentId", ADMIN_SUB)))
                .andExpect(jsonPath("$.scope").value("AGENT"))
                .andExpect(jsonPath("$.panels.agents").doesNotExist());
    }

    @Test
    void rejectsAnInvertedWindowWithoutCallingUpstream() throws Exception {
        mockMvc.perform(asAgent(get("/api/dashboard/overview")
                        .param("from", "2026-09-20")
                        .param("to", "2026-01-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("'from' must not be after 'to'"));
    }

    @Test
    void rejectsAWindowLongerThanTheMaximum() throws Exception {
        mockMvc.perform(asAgent(get("/api/dashboard/overview")
                        .param("from", "2020-01-01")
                        .param("to", "2026-09-20")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void servesEveryEndpointWithItsOwnPanels() throws Exception {
        mockMvc.perform(asAgent(get("/api/dashboard/portfolio")))
                .andExpect(jsonPath("$.panels.clients").exists())
                .andExpect(jsonPath("$.panels.accounts").exists())
                // Derived from the other two, so unavailable for the same reason.
                .andExpect(jsonPath("$.panels.accountsPerClient.reason").value("upstream_not_deployed"))
                .andExpect(jsonPath("$.panels.transactions").doesNotExist());

        mockMvc.perform(asAgent(get("/api/dashboard/action-items")))
                .andExpect(jsonPath("$.panels.clients").exists())
                .andExpect(jsonPath("$.panels.transactions").exists());

        // Ingestion freshness is left out of the first cut (ADR-0038).
        mockMvc.perform(asAdmin(get("/api/dashboard/transactions")))
                .andExpect(jsonPath("$.panels.transactions").exists())
                .andExpect(jsonPath("$.panels.ingestion").doesNotExist());
    }

    @Test
    void rejectsAnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsASortMetricOutsideTheContractEnum() throws Exception {
        // The closed set is what makes ADR-0038's refusal of a derived rank
        // enforceable rather than merely stated, so an unknown metric must be
        // refused rather than accepted and echoed back as sortedBy.
        mockMvc.perform(asAdmin(get("/api/dashboard/agents").param("sort", "compositeScore")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        "'sort' must be one of clientsOnboarded, verificationRate, accountsOpened, bookedInitialDeposits"));
    }

    @Test
    void acceptsEverySortMetricTheContractDeclares() throws Exception {
        for (String metric : new String[] {
                "clientsOnboarded", "verificationRate", "accountsOpened", "bookedInitialDeposits"}) {
            mockMvc.perform(asAdmin(get("/api/dashboard/agents").param("sort", metric)))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    @Test
    void rejectsAStaleDaysOutsideTheContractRange() throws Exception {
        // dashboard-api.yaml fixes staleDays to 1..3650. The ceiling guards
        // downstream date arithmetic; it is not a cost limit, because a
        // higher threshold matches fewer rows.
        mockMvc.perform(asAgent(get("/api/dashboard/action-items").param("staleDays", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("staleDays must be greater than or equal to 1"));
        mockMvc.perform(asAgent(get("/api/dashboard/action-items").param("staleDays", "3651")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsAMultiYearStaleDaysThreshold() throws Exception {
        // "Unverified for more than two years" is a legitimate data-hygiene
        // question, and cheaper to answer than the 7-day default, so the
        // ceiling must not refuse it.
        mockMvc.perform(asAgent(get("/api/dashboard/action-items").param("staleDays", "730")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void reportsAnUnparseableParameterInTheSharedErrorShape() throws Exception {
        // Not Spring Boot's default error body, which carries `path` and no
        // `message`. One error format across the CRM is the promise.
        mockMvc.perform(asAgent(get("/api/dashboard/action-items").param("staleDays", "abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    // Authorities are set explicitly rather than left to be derived from the
    // token: the jwt() post-processor applies Spring's default scope-based
    // converter, not this service's JwtRoleConverter. The cognito:groups
    // mapping those authorities come from in production is covered by
    // JwtRoleConverterTest.
    private MockHttpServletRequestBuilder asAgent(MockHttpServletRequestBuilder request) {
        return request.with(jwt()
                .jwt(j -> j.subject(AGENT_SUB))
                .authorities(new SimpleGrantedAuthority("ROLE_AGENT")));
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.with(jwt()
                .jwt(j -> j.subject(ADMIN_SUB))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
