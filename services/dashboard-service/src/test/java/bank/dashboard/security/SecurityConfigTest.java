package bank.dashboard.security;

import bank.dashboard.testsupport.DashboardTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Zero-Trust chain at this service's edge. API Gateway validates the same
 * token first, but these assertions are what make that first validation a
 * defence in depth rather than the only check.
 */
@DashboardTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsAnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsReachableWithoutAToken() throws Exception {
        // The deploy pipeline's one unauthenticated URL. Spring's
        // show-details defaults to "never", so the body carries no component
        // detail.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void apiDocsAreNotPublishedOutsideTheLocalProfile() throws Exception {
        // springdoc is disabled unless the `local` profile is active, so the
        // permitAll() matcher on /v3/api-docs guards a path that is not served.
        // Opt-in rather than opt-out: a deployment that forgets to set a
        // profile keeps the spec unpublished.
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isNotFound());
    }

    @Test
    void acceptsAValidToken() throws Exception {
        // Asserts only that authentication succeeded and the request reached
        // the application. The exact status is deliberately not pinned here:
        // it is 503 today because every upstream is absent, and it becomes 200
        // as services land. That behaviour belongs to DashboardControllerTest,
        // and pinning it here would make an unrelated test fail when an
        // upstream is wired up.
        mockMvc.perform(get("/api/dashboard/overview")
                        .with(jwt().jwt(j -> j.subject("11111111-1111-1111-1111-111111111111"))))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .describedAs("authenticated request must not be rejected")
                        .isNotIn(401, 403));
    }
}
