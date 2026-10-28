package bank.clienthealth.transaction.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import bank.clienthealth.transaction.application.TransactionReplayService;
import bank.clienthealth.transaction.application.TransactionReplayService.IncompleteReplayException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class HttpTransactionReplaySourceTest {
    private static final Instant FROM = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");
    private MockRestServiceServer server;
    private TransactionReplayService replay;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        TransactionReplayProperties properties = properties();
        var tokens = new TransactionReplayTokenProvider(client, properties,
                Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC));
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        replay = new TransactionReplayService(
                new HttpTransactionReplaySource(client, tokens, properties, json), 2, 3, 5);
    }

    @Test
    void authenticatesCachesTokenAndFollowsEveryCursor() {
        expectToken();
        expectPage(null, page("txn-1", "next-page"));
        expectPage("next-page", page("txn-2", null));

        var result = replay.replay(FROM, TO);

        assertThat(result.pagesFetched()).isEqualTo(2);
        assertThat(result.transactions()).extracting(transaction -> transaction.transactionId())
                .containsExactly("txn-1", "txn-2");
        server.verify();
    }

    @Test
    void rejectsMalformedPageWithUnknownData() {
        expectToken();
        expectPage(null, page("txn-1", null).replace(
                "\"nextCursor\":null", "\"nextCursor\":null,\"description\":\"forbidden\""));

        assertThatThrownBy(() -> replay.replay(FROM, TO))
                .isInstanceOf(IncompleteReplayException.class);
        server.verify();
    }

    @Test
    void rejectsSourceFingerprintOutsideTheProviderContract() {
        expectToken();
        expectPage(null, page("txn-1", null).replace(
                "synthetic-source-a1b2c3d4e5f6", "synthetic-source-1"));

        assertThatThrownBy(() -> replay.replay(FROM, TO))
                .isInstanceOf(IncompleteReplayException.class);
        server.verify();
    }

    @Test
    void failsClosedWhenALaterPageIsUnavailable() {
        expectToken();
        expectPage(null, page("txn-1", "next-page"));
        server.expect(requestTo(containsString("https://transactions.test/internal/v1/transactions")))
                .andExpect(queryParam("cursor", "next-page"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> replay.replay(FROM, TO))
                .isInstanceOf(IncompleteReplayException.class);
        server.verify();
    }

    @Test
    void convertsTransportTimeoutToIncompleteReplay() {
        expectToken();
        server.expect(requestTo(containsString("https://transactions.test/internal/v1/transactions")))
                .andRespond(request -> { throw new ResourceAccessException("synthetic timeout"); });

        assertThatThrownBy(() -> replay.replay(FROM, TO))
                .isInstanceOf(IncompleteReplayException.class);
        server.verify();
    }

    @Test
    void validatesScopeAndBounds() {
        assertThatThrownBy(() -> new TransactionReplayProperties(
                URI.create("https://transactions.test/internal/v1/transactions"),
                URI.create("https://idp.test/oauth2/token"), "client", "secret", "wrong-scope",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 2, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crm-transactions/read");
        assertThatThrownBy(() -> new TransactionReplayProperties(
                URI.create("https://transactions.test/internal/v1/transactions"),
                URI.create("https://idp.test/oauth2/token"), "client", "secret",
                "crm-transactions/read", Duration.ZERO, Duration.ofSeconds(1), 100, 2, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeouts");
        assertThatThrownBy(() -> new TransactionReplayProperties(
                URI.create("http://transactions.test/internal/v1/transactions"),
                URI.create("https://idp.test/oauth2/token"), "client", "secret",
                "crm-transactions/read", Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 2, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new TransactionReplayProperties(
                URI.create("https://transactions.test/internal/v1/transactions"),
                URI.create("http://idp.test/oauth2/token"), "client", "secret",
                "crm-transactions/read", Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 2, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    private void expectToken() {
        server.expect(requestTo("https://idp.test/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic aGVhbHRoLWNsaWVudDpzZWNyZXQ="))
                .andExpect(content().string(containsString("scope=crm-transactions%2Fread")))
                .andRespond(withSuccess("{\"access_token\":\"service-token\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));
    }

    private void expectPage(String cursor, String response) {
        var expectation = server.expect(
                        requestTo(containsString("https://transactions.test/internal/v1/transactions")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer service-token"))
                .andExpect(queryParam("from", FROM.toString()))
                .andExpect(queryParam("to", TO.toString()))
                .andExpect(queryParam("limit", "2"));
        if (cursor != null) expectation.andExpect(queryParam("cursor", cursor));
        expectation.andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private static TransactionReplayProperties properties() {
        return new TransactionReplayProperties(
                URI.create("https://transactions.test/internal/v1/transactions"),
                URI.create("https://idp.test/oauth2/token"), "health-client", "secret",
                "crm-transactions/read", Duration.ofSeconds(1), Duration.ofSeconds(1), 2, 3, 5);
    }

    private static String page(String transactionId, String nextCursor) {
        String cursor = nextCursor == null ? "null" : "\"" + nextCursor + "\"";
        return """
                {"schemaVersion":"1","items":[{
                  "transactionId":"%s","clientId":"client-1","accountId":"account-1",
                  "type":"Deposit","amount":"42.00","currency":"SGD",
                  "transactionDate":"2026-05-01T00:00:00Z","status":"Completed",
                  "activityKind":"Automated","sourceFingerprint":"synthetic-source-a1b2c3d4e5f6"
                }],"nextCursor":%s}
                """.formatted(transactionId, cursor);
    }
}
