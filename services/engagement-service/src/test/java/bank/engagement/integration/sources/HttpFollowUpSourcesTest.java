package bank.engagement.integration.sources;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpFollowUpSourcesTest {

    private static final String CLIENT = "fb2c22ef-69fc-47de-b67f-5c1d0cfc795a";
    private static final UUID ALERT = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final String TASK_URL = "https://alb.example.test/api/relationship-health/tasks/task-000001";
    private static final String ALERT_URL = "https://alb.example.test/api/alerts/" + ALERT;

    private MockRestServiceServer server;
    private HttpFollowUpSources sources;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sources = new HttpFollowUpSources(builder.build(), new CurrentUser(), new ObjectMapper(),
                new FollowUpSourceProperties("https://alb.example.test/", "https://alb.example.test",
                        Duration.ofSeconds(5)));
        Jwt jwt = Jwt.withTokenValue("agent-token").header("alg", "none").subject("agent-sub").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** The task response from docs/api/client-health-monitor-contracts.md; only clientId is read. */
    @Test
    void readsATasksClientWithTheAgentsToken() {
        server.expect(requestTo(TASK_URL))
                .andExpect(header("Authorization", "Bearer agent-token"))
                .andRespond(withSuccess("""
                        {"schemaVersion":"1","taskId":"task-000001","clientId":"%s",
                         "assignedAgentSubject":"agent-sub","status":"OPEN","severity":"MEDIUM",
                         "explanation":{"text":"Review suggested because a recurring credit was not observed.",
                                        "source":"TEMPLATE_FALLBACK","generatedAt":"2026-09-29T04:05:05Z"},
                         "evidence":[{"type":"RECURRING_CREDIT_INTERRUPTION","accountId":"acc-000001",
                                      "ruleVersion":"recurring-credit-v1","severity":"MEDIUM"}],
                         "version":1,"createdAt":"2026-09-29T04:05:05Z","updatedAt":"2026-09-29T04:05:05Z"}
                        """.formatted(CLIENT), MediaType.APPLICATION_JSON));

        assertThat(sources.healthTaskClient("task-000001")).isEqualTo(CLIENT);
        server.verify();
    }

    @Test
    void readsAnAlertsClientReference() {
        server.expect(requestTo(ALERT_URL))
                .andExpect(header("Authorization", "Bearer agent-token"))
                .andRespond(withSuccess("""
                        {"id":"%s","clientRef":"%s","instrumentId":"SGX:D05","headline":"Synthetic headline",
                         "status":"NEW","score":0.82,"createdAt":"2026-10-30T02:00:00Z","version":1}
                        """.formatted(ALERT, CLIENT), MediaType.APPLICATION_JSON));

        assertThat(sources.alertClient(ALERT)).isEqualTo(CLIENT);
        server.verify();
    }

    @Test
    void aTaskIdIsEncodedIntoThePath() {
        server.expect(requestTo("https://alb.example.test/api/relationship-health/tasks/task%2F1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> sources.healthTaskClient("task/1")).isInstanceOf(SourceNotFoundException.class);
    }

    @Test
    void refusalsAreNotFound() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND}) {
            server.reset();
            server.expect(requestTo(ALERT_URL)).andRespond(withStatus(status));
            assertThatThrownBy(() -> sources.alertClient(ALERT)).isInstanceOf(SourceNotFoundException.class);
        }
    }

    @Test
    void errorsAndUnreadableOrEmptyBodiesAreUnavailable() {
        server.expect(requestTo(TASK_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> sources.healthTaskClient("task-000001"))
                .isInstanceOf(SourceUnavailableException.class);

        server.reset();
        server.expect(requestTo(TASK_URL)).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));
        assertThatThrownBy(() -> sources.healthTaskClient("task-000001"))
                .isInstanceOf(SourceUnavailableException.class);

        server.reset();
        server.expect(requestTo(ALERT_URL)).andRespond(withSuccess("{\"id\":\"x\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> sources.alertClient(ALERT)).isInstanceOf(SourceUnavailableException.class);
    }

    @Test
    void noBaseUrlFailsClosed() {
        HttpFollowUpSources unconfigured = new HttpFollowUpSources(RestClient.create(), new CurrentUser(),
                new ObjectMapper(), new FollowUpSourceProperties("", null, null));

        assertThatThrownBy(() -> unconfigured.healthTaskClient("task-000001"))
                .isInstanceOf(SourceUnavailableException.class);
        assertThatThrownBy(() -> unconfigured.alertClient(ALERT)).isInstanceOf(SourceUnavailableException.class);
    }
}
