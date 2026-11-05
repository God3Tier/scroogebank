package bank.engagement.integration.sources;

import bank.engagement.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpReportPackagesTest {

    private static final UUID EVENT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ALERT = UUID.fromString("30000000-0000-4000-8000-00000000000a");
    private static final String URL = "https://alb.example.test/api/alerts/events/" + EVENT + "/report/delivery-package";

    private MockRestServiceServer server;
    private HttpReportPackages packages;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        packages = new HttpReportPackages(builder.build(), new CurrentUser(), new ObjectMapper().registerModule(new JavaTimeModule()),
                new FollowUpSourceProperties("https://alb.example.test", "https://alb.example.test", Duration.ofSeconds(5)));
        Jwt jwt = Jwt.withTokenValue("agent-token").header("alg", "none").subject("agent-sub").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void asksWithTheAgentsTokenAndReadsTheReportAndRecipients() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer agent-token"))
                .andExpect(content().json("{\"expectedReportVersion\":2,\"alertIds\":[\"" + ALERT + "\"]}"))
                .andRespond(withSuccess("""
                        {"eventId":"%s","reportId":"20000000-0000-4000-8000-000000000002","version":2,
                         "schemaVersion":"alert-client-report-v2","report":{"headline":"H"},"reportHash":"abc",
                         "sources":[{"id":"s1","title":"T","url":"https://a.example/1","domain":"a.example",
                                     "publishedAt":"2026-11-01T02:00:00Z","retrievedAt":"2026-11-01T04:00:00Z","usedInReport":true}],
                         "recipients":[{"alertId":"%s","outcome":"OK","clientRef":"c","instrumentId":"I","issuer":"Issuer"},
                                       {"alertId":"%s","outcome":"NOT_AUTHORISED","clientRef":null,"instrumentId":null,"issuer":null}],
                         "somethingNew":1}
                        """.formatted(EVENT, ALERT, UUID.randomUUID()), MediaType.APPLICATION_JSON));

        ReportPackages.Package result = packages.fetch(EVENT, 2, List.of(ALERT));

        assertThat(result.version()).isEqualTo(2);
        assertThat(result.sources()).singleElement().satisfies(s -> assertThat(s.retrievedAt()).isNotNull());
        assertThat(result.recipients().get(0).authorised()).isTrue();
        assertThat(result.recipients().get(1).authorised()).isFalse();
    }

    @Test
    void mapsTheStatusesTheContractNames() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> packages.fetch(EVENT, 1, List.of(ALERT))).isInstanceOf(StaleReportVersionException.class);

        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> packages.fetch(EVENT, 1, List.of(ALERT))).isInstanceOf(SourceNotFoundException.class);

        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> packages.fetch(EVENT, 1, List.of(ALERT))).isInstanceOf(SourceUnavailableException.class);
    }
}
