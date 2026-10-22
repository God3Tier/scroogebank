package bank.dashboard.upstream.http;

import bank.dashboard.config.DashboardProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UpstreamWarmUpTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();

    @Test
    void callsEachUpstreamsHealthEndpointWithoutAToken() {
        // The dashboard holds no credential of its own (ADR-0033), and the
        // health endpoints need none.
        server.expect(requestTo("http://user-service:8081/actuator/health")).andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://client-service:8080/health"))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://account-service:8083/health"))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        warmUp("http://user-service:8081", "https://client-service:8080/", "https://account-service:8083").run(null);

        server.verify();
    }

    @Test
    void skipsAnUpstreamThatIsNotWiredUp() {
        server.expect(requestTo("https://client-service:8080/health"))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        warmUp("", "https://client-service:8080", " ").run(null);

        server.verify();
    }

    @Test
    void anUpstreamThatFailsDoesNotStopTheServiceStarting() {
        server.expect(requestTo("http://user-service:8081/actuator/health"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("https://client-service:8080/health"))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });
        server.expect(requestTo("https://account-service:8083/health"))
                .andRespond(withSuccess("not json", MediaType.TEXT_PLAIN));

        assertThatNoException().isThrownBy(() ->
                warmUp("http://user-service:8081", "https://client-service:8080", "https://account-service:8083").run(null));
        server.verify();
    }

    private UpstreamWarmUp warmUp(String userService, String clientService, String accountService) {
        return new UpstreamWarmUp(builder.build(), new DashboardProperties(
                new DashboardProperties.Window(30, 365),
                new DashboardProperties.Cache(60, 1000),
                new DashboardProperties.Upstream(2000, 3000, userService, clientService, accountService)));
    }
}
