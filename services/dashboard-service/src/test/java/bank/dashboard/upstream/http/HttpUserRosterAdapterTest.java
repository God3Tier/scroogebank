package bank.dashboard.upstream.http;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.PanelReason;
import bank.dashboard.exception.UpstreamUnavailableException;
import bank.dashboard.security.CurrentUser;
import bank.dashboard.upstream.dto.AgentSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static bank.dashboard.contract.OpenApiContract.upstream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpUserRosterAdapterTest {

    private static final String BASE_URL = "http://user-service.internal:8081";

    private MockRestServiceServer server;
    private CurrentUser currentUser;
    private HttpUserRosterAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        currentUser = mock(CurrentUser.class);
        when(currentUser.tokenValue()).thenReturn("the-callers-token");
        adapter = adapter(builder, BASE_URL);
    }

    @Test
    void readsEveryPageOfTheRoster() {
        server.expect(requestTo(page(0)))
                .andRespond(upstream("users-api.yaml", withSuccess(userPage(0, 2, """
                        {"id":"00000000-0000-4000-8000-000000000001","idpSub":"sub-ada","firstName":"Ada","lastName":"Lim","email":"ada@x.com",
                         "role":"AGENT","enabled":true,"createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z"}
                        """), MediaType.APPLICATION_JSON)));
        server.expect(requestTo(page(1)))
                .andRespond(upstream("users-api.yaml", withSuccess(userPage(1, 2, """
                        {"id":"00000000-0000-4000-8000-000000000002","idpSub":"sub-ben","firstName":"Ben","lastName":"Chua","email":"ben@x.com",
                         "role":"AGENT","enabled":false,"createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z"}
                        """), MediaType.APPLICATION_JSON)));

        List<AgentSummary> agents = adapter.agents();

        assertThat(agents).extracting(AgentSummary::idpSub).containsExactly("sub-ada", "sub-ben");
        assertThat(agents.get(0).displayName()).isEqualTo("Ada Lim");
        assertThat(agents.get(1).enabled()).isFalse();
        server.verify();
    }

    @Test
    void forwardsTheCallersOwnToken() {
        // The dashboard holds no service credential. Forwarding the caller's
        // token is what keeps it unable to read anything the caller could not
        // read directly, and leaves user-service deciding who sees the roster.
        server.expect(requestTo(page(0)))
                .andExpect(header("Authorization", "Bearer the-callers-token"))
                .andRespond(upstream("users-api.yaml", withSuccess(userPage(0, 1, ""), MediaType.APPLICATION_JSON)));

        adapter.agents();

        server.verify();
    }

    @Test
    void treatsAnEmptyRosterAsAnEmptyList() {
        // user-service reports zero pages for an empty result.
        server.expect(requestTo(page(0)))
                .andRespond(upstream("users-api.yaml", withSuccess(userPage(0, 0, ""), MediaType.APPLICATION_JSON)));

        assertThat(adapter.agents()).isEmpty();
    }

    @Test
    void reportsAForbiddenUpstreamAsRefusalNotFailure() {
        // user-service refusing an agent is correct behaviour, so it must not
        // be reported as an error -- that would make the system look broken
        // when it is working.
        server.expect(requestTo(page(0))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adapter.agents())
                .isInstanceOf(UpstreamUnavailableException.class)
                .extracting(ex -> ((UpstreamUnavailableException) ex).reason())
                .isEqualTo(PanelReason.UPSTREAM_FORBIDDEN);
    }

    @Test
    void reportsAServerErrorAsAnError() {
        server.expect(requestTo(page(0))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> adapter.agents())
                .isInstanceOf(UpstreamUnavailableException.class)
                .extracting(ex -> ((UpstreamUnavailableException) ex).reason())
                .isEqualTo(PanelReason.UPSTREAM_ERROR);
    }

    @Test
    void refusesARosterTooLongToReadWhole() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer endless = MockRestServiceServer.bindTo(builder).build();
        for (int page = 0; page < HttpUserRosterAdapter.MAX_PAGES; page++) {
            endless.expect(requestTo(page(page)))
                    .andRespond(upstream("users-api.yaml", withSuccess(userPage(page, 1000, ""), MediaType.APPLICATION_JSON)));
        }

        assertThatThrownBy(() -> adapter(builder, BASE_URL).agents())
                .isInstanceOf(UpstreamUnavailableException.class)
                .extracting(ex -> ((UpstreamUnavailableException) ex).reason())
                .isEqualTo(PanelReason.UPSTREAM_ERROR);
    }

    @Test
    void treatsAnUnconfiguredBaseUrlAsNotDeployed() {
        // A blank base URL is a supported state, not a misconfiguration: the
        // panel degrades in exactly the same words as an absent upstream.
        assertThatThrownBy(() -> adapter(RestClient.builder(), "").agents())
                .isInstanceOf(UpstreamUnavailableException.class)
                .extracting(ex -> ((UpstreamUnavailableException) ex).reason())
                .isEqualTo(PanelReason.UPSTREAM_NOT_DEPLOYED);
    }

    @Test
    void toleratesATrailingSlashOnTheConfiguredBaseUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer trailingSlashServer = MockRestServiceServer.bindTo(builder).build();
        trailingSlashServer.expect(requestTo(page(0)))
                .andRespond(upstream("users-api.yaml", withSuccess(userPage(0, 1, ""), MediaType.APPLICATION_JSON)));

        adapter(builder, BASE_URL + "/").agents();

        trailingSlashServer.verify();
    }

    private HttpUserRosterAdapter adapter(RestClient.Builder builder, String baseUrl) {
        return new HttpUserRosterAdapter(new UpstreamClient(builder.build(), currentUser), properties(baseUrl));
    }

    private static String page(int page) {
        return BASE_URL + "/api/users?role=AGENT&page=" + page + "&size=" + HttpUserRosterAdapter.PAGE_SIZE;
    }

    private static String userPage(int page, int totalPages, String content) {
        return "{\"content\":[" + content + "],\"page\":" + page + ",\"size\":100,\"totalElements\":"
                + totalPages + ",\"totalPages\":" + totalPages + "}";
    }

    static DashboardProperties properties(String userServiceUrl) {
        return new DashboardProperties(
                new DashboardProperties.Window(30, 365),
                new DashboardProperties.Cache(60, 1000),
                new DashboardProperties.Upstream(2000, 3000, userServiceUrl, "", "", ""));
    }
}
