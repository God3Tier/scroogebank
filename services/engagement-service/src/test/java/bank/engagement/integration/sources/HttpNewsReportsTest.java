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

class HttpNewsReportsTest {

    private static final UUID EVENT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ALERT = UUID.fromString("30000000-0000-4000-8000-00000000000a");
    private static final UUID APPROVAL = UUID.fromString("40000000-0000-4000-8000-00000000000a");
    private static final String BASE = "https://alb.example.test";

    private MockRestServiceServer server;
    private HttpNewsReports reports;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        reports = new HttpNewsReports(client, client, new CurrentUser(), new ObjectMapper().registerModule(new JavaTimeModule()),
                new FollowUpSourceProperties(BASE, BASE + "/", Duration.ofSeconds(5), Duration.ofSeconds(15)));
        Jwt jwt = Jwt.withTokenValue("agent-token").header("alg", "none").subject("agent-sub").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsThePackageWithTheAgentsToken() {
        server.expect(requestTo(BASE + "/api/alerts/approved-reports/package")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer agent-token"))
                .andExpect(content().json("{\"eventId\":\"" + EVENT + "\",\"alertIds\":[\"" + ALERT + "\"]}"))
                .andRespond(withSuccess("""
                        [{"alertId":"%s","outcome":"OK","approvalId":"%s","clientRef":"55555555-5555-4555-8555-555555555555",
                          "instrumentId":"AAPL","issuer":"Apple Inc","headline":"H","newsDate":"2026-11-01T02:00:00Z",
                          "validUntil":"2026-11-08T02:00:00Z","edited":false,"finalHash":"h","summaryLine":"S","somethingNew":1}]
                        """.formatted(ALERT, APPROVAL), MediaType.APPLICATION_JSON));

        assertThat(reports.packageFor(EVENT, List.of(ALERT))).singleElement().satisfies(item -> {
            assertThat(item.ok()).isTrue();
            assertThat(item.approvalId()).isEqualTo(APPROVAL);
            assertThat(item.summaryLine()).isEqualTo("S");
        });
        server.verify();
    }

    @Test
    void readsTheCombinedPdf() {
        server.expect(requestTo(BASE + "/api/alerts/approved-reports/pdf"))
                .andExpect(content().json("{\"approvalIds\":[\"" + APPROVAL + "\"],\"preparedBy\":\"Alex Tan\"}"))
                .andRespond(withSuccess("""
                        {"fileName":"Portfolio-update-2026-11-07.pdf","documentDate":"2026-11-07","pdfBase64":"JVBERi0=",
                         "pdfHash":"p","pageCount":4,"sections":[{"approvalId":"%s","issuer":"Apple Inc","headline":"H",
                         "startPage":3,"finalHash":"h"}]}
                        """.formatted(APPROVAL), MediaType.APPLICATION_JSON));

        NewsReports.Pdf pdf = reports.combinedPdf(List.of(APPROVAL), "Alex Tan");

        assertThat(new String(pdf.bytes())).isEqualTo("%PDF-");
        assertThat(pdf.sections()).singleElement().satisfies(section -> assertThat(section.startPage()).isEqualTo(3));
    }

    @Test
    void refusalsAreNotFoundAndEverythingElseIsUnavailable() {
        server.expect(requestTo(BASE + "/api/alerts/send-check")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> reports.sendCheck(List.of(ALERT))).isInstanceOf(SourceNotFoundException.class);

        server.reset();
        server.expect(requestTo(BASE + "/api/alerts/sent-out")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> reports.sentOut(List.of(ALERT), UUID.randomUUID(), "m"))
                .isInstanceOf(SourceUnavailableException.class);

        HttpNewsReports unconfigured = new HttpNewsReports(RestClient.create(), RestClient.create(), new CurrentUser(),
                new ObjectMapper(), new FollowUpSourceProperties(null, "", null, null));
        assertThatThrownBy(() -> unconfigured.sendCheck(List.of(ALERT))).isInstanceOf(SourceUnavailableException.class);
    }
}
