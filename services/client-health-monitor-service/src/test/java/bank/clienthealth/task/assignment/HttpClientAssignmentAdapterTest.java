package bank.clienthealth.task.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import bank.clienthealth.task.port.ClientAssignmentPort.AssignmentRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpClientAssignmentAdapterTest {
    private MockRestServiceServer server;
    private HttpClientAssignmentAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        var tokens = new ClientCredentialsTokenProvider(restClient,
                Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC),
                "https://idp.test/oauth2/token", "health-client", "secret", "clients.assignment.read");
        adapter = new HttpClientAssignmentAdapter(restClient, tokens, "https://clients.test");
    }

    @Test
    void obtainsScopedTokenAndMapsContractResponse() {
        expectToken();
        server.expect(requestTo("https://clients.test/internal/v1/clients/client-1/assignment"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer service-token"))
                .andRespond(withSuccess("""
                        {"schemaVersion":"1","clientId":"client-1",
                         "assignedAgentSubject":"agent-new","active":true,
                         "assignmentVersion":7,"updatedAt":"2026-10-22T23:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        var assignment = adapter.resolve("client-1");

        assertThat(assignment.agentSubject()).isEqualTo("agent-new");
        assertThat(assignment.assignmentVersion()).isEqualTo(7);
        server.verify();
    }

    @Test
    void mapsMissingClientToRejectedAssignment() {
        expectToken();
        server.expect(requestTo("https://clients.test/internal/v1/clients/missing/assignment"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> adapter.resolve("missing"))
                .isInstanceOf(AssignmentRejectedException.class);
        server.verify();
    }

    private void expectToken() {
        server.expect(requestTo("https://idp.test/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic aGVhbHRoLWNsaWVudDpzZWNyZXQ="))
                .andRespond(withSuccess("{\"access_token\":\"service-token\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));
    }
}
