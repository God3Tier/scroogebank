package bank.engagement.integration.clients;

import bank.engagement.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

import static bank.engagement.contract.OpenApiContract.upstream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpClientDirectoryTest {

    private static final UUID CLIENT = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final String URL = "http://client-service.test/api/clients/" + CLIENT;

    private MockRestServiceServer server;
    private HttpClientDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        directory = new HttpClientDirectory(builder.build(), new CurrentUser(), new ObjectMapper(),
                new ClientServiceProperties("http://client-service.test/", Duration.ofSeconds(5)));
        Jwt jwt = Jwt.withTokenValue("agent-token").header("alg", "none").subject("agent-sub").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** client-service writes JSON as text/plain (no Content-Type is set), exactly like this. */
    @Test
    void readsTheClientWithTheAgentsToken() {
        server.expect(requestTo(URL))
                .andExpect(header("Authorization", "Bearer agent-token"))
                .andRespond(upstream("clients-api.yaml", withSuccess("""
                        {"client_id":"%s","first_name":"Synthetic","last_name":"Client",
                         "date_of_birth":"1990-01-01T00:00:00Z","gender":"F",
                         "email":"synthetic.client@example.test","phone_number":"+6590000000",
                         "address":"1 Synthetic Road","city":"Singapore","state":"Singapore",
                         "country":"Singapore","postal_code":"000001","verification_status":"VERIFIED",
                         "assigned_agent_id":"agent-sub","created_at":"2026-09-01T00:00:00Z",
                         "updated_at":"2026-09-01T00:00:00Z"}
                        """.formatted(CLIENT), MediaType.APPLICATION_JSON)));

        ClientRecord client = directory.findOwned(CLIENT);

        assertThat(client.firstName()).isEqualTo("Synthetic");
        assertThat(client.email()).isEqualTo("synthetic.client@example.test");
        assertThat(client.country()).isEqualTo("Singapore");
        assertThat(client.toString()).doesNotContain("Synthetic").doesNotContain("example.test");
        server.verify();
    }

    @Test
    void refusalsAreNotFound() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> directory.findOwned(CLIENT)).isInstanceOf(ClientNotFoundException.class);

        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> directory.findOwned(CLIENT)).isInstanceOf(ClientNotFoundException.class);
    }

    @Test
    void errorsAndUnreadableBodiesAreUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> directory.findOwned(CLIENT)).isInstanceOf(ClientServiceUnavailableException.class);

        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));
        assertThatThrownBy(() -> directory.findOwned(CLIENT)).isInstanceOf(ClientServiceUnavailableException.class);
    }

    @Test
    void noBaseUrlFailsClosed() {
        HttpClientDirectory unconfigured = new HttpClientDirectory(RestClient.create(), new CurrentUser(),
                new ObjectMapper(), new ClientServiceProperties("", null));

        assertThatThrownBy(() -> unconfigured.findOwned(CLIENT)).isInstanceOf(ClientServiceUnavailableException.class);
    }
}
