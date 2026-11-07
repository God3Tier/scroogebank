package bank.alert.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TavilySearchProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-23T08:00:00Z");

    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile Reply reply;

    record Reply(int status, String contentType, String body) {
        static Reply json(String body) {
            return new Reply(200, "application/json", body);
        }
    }

    @BeforeEach
    void startTavily() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", this::handle);
        server.start();
        reply = Reply.json("""
                {"results":[{"id":"result-1","title":" SGX  reports\\n earnings ",
                "url":"https://www.sgx.com/news/1","content":"Public  excerpt",
                "published_date":"Fri, 23 Oct 2026 07:30:00 GMT"}]}
                """);
    }

    @AfterEach
    void stopTavily() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(MAPPER.readTree(exchange.getRequestBody()));
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", reply.contentType());
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private TavilySearchProvider provider() {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
        return new TavilySearchProvider(endpoint, "tvly-test", Duration.ofSeconds(5), 16_384,
                MAPPER, Clock.fixed(NOW, ZoneOffset.UTC), HttpClient.newHttpClient());
    }

    private static PublicSearchRequest request() {
        return new PublicSearchRequest("SYN1 earnings", List.of("sgx.com", "mas.gov.sg"),
                Instant.parse("2026-10-23T04:00:00Z"), NOW, 5);
    }

    @Test
    void aSearchMayAskForTwentyResultsAndNoMore() {
        Instant from = Instant.parse("2026-10-23T04:00:00Z");
        assertThat(new PublicSearchRequest("SYN1 earnings", List.of("sgx.com"), from, NOW, 20).maximumResults()).isEqualTo(20);
        assertThatThrownBy(() -> new PublicSearchRequest("SYN1 earnings", List.of("sgx.com"), from, NOW, 21))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sendsOnlyBoundedNewsFiltersAndAuthenticatesInTheHeader() {
        provider().search(request());

        assertThat(authorizations).containsExactly("Bearer tvly-test");
        JsonNode body = requests.getFirst();
        assertThat(body.path("query").asText()).isEqualTo("SYN1 earnings");
        assertThat(body.path("topic").asText()).isEqualTo("news");
        assertThat(body.path("search_depth").asText()).isEqualTo("basic");
        assertThat(body.path("chunks_per_source").asInt()).isOne();
        assertThat(body.path("max_results").asInt()).isEqualTo(5);
        assertThat(body.path("start_date").asText()).isEqualTo("2026-10-23");
        assertThat(body.path("end_date").asText()).isEqualTo("2026-10-24");
        assertThat(body.path("filter_by_published_date").asBoolean()).isTrue();
        // Tavily is not told which sites to use; the approved sites are applied to what comes back.
        assertThat(body.has("include_domains")).isFalse();
        assertThat(body.has("include_domains_mode")).isFalse();
        assertThat(body.path("include_answer").asBoolean()).isFalse();
        assertThat(body.path("include_raw_content").asBoolean()).isFalse();
        assertThat(body.has("api_key")).isFalse();
    }

    @Test
    void mapsTheMinimumExcerptAndPublishedDate() {
        assertThat(provider().search(request())).singleElement().satisfies(result -> {
            assertThat(result.providerResultId()).isEqualTo("result-1");
            assertThat(result.url()).isEqualTo("https://www.sgx.com/news/1");
            assertThat(result.title()).isEqualTo("SGX reports earnings");
            assertThat(result.publishedAt()).isEqualTo(Instant.parse("2026-10-23T07:30:00Z"));
            assertThat(result.retrievedAt()).isEqualTo(NOW);
            assertThat(result.snippet()).isEqualTo("Public excerpt");
            assertThat(result.contentHash()).matches("[0-9a-f]{64}");
        });
    }

    @Test
    void dropsOutOfScopeDomainsDatesAndUndatedResultsEvenIfTheProviderReturnsThem() {
        reply = Reply.json("""
                {"results":[
                  {"title":"Wrong domain","url":"https://evil.example/news","content":"x",
                   "published_date":"Fri, 23 Oct 2026 07:30:00 GMT"},
                  {"title":"Too old","url":"https://www.sgx.com/old","content":"x",
                   "published_date":"2026-10-23T03:59:59Z"},
                  {"title":"No date","url":"https://www.sgx.com/unknown","content":"x"}]}
                """);

        assertThat(provider().search(request())).isEmpty();
    }

    @Test
    void rejectsOversizedOrUnboundedResponses() {
        reply = Reply.json("x".repeat(20_000));
        assertThatThrownBy(() -> provider().search(request()))
                .isInstanceOfSatisfying(SearchProviderException.class,
                        failure -> assertThat(failure.code()).isEqualTo("INVALID_PROVIDER_RESPONSE"));

        reply = Reply.json("{" + "\"results\":[{} ,{} ,{} ,{} ,{} ,{}]}" );
        assertThatThrownBy(() -> provider().search(request()))
                .isInstanceOfSatisfying(SearchProviderException.class,
                        failure -> assertThat(failure.code()).isEqualTo("INVALID_PROVIDER_RESPONSE"));
    }

    @Test
    void classifiesAccessQuotaAndTransientFailuresWithoutLeakingTheBody() {
        reply = new Reply(401, "application/json", "{\"detail\":{\"error\":\"secret server detail\"}}");
        assertFailure("PROVIDER_ACCESS_DENIED", false);

        reply = new Reply(432, "application/json", "{}");
        assertFailure("PROVIDER_QUOTA_EXCEEDED", false);

        reply = new Reply(429, "application/json", "{}");
        assertFailure("PROVIDER_UNAVAILABLE", true);

        reply = new Reply(422, "application/json", "{}");
        assertFailure("PROVIDER_REJECTED", false);
    }

    private void assertFailure(String code, boolean retryable) {
        assertThatThrownBy(() -> provider().search(request()))
                .isInstanceOfSatisfying(SearchProviderException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.retryable()).isEqualTo(retryable);
                    assertThat(failure.getMessage()).doesNotContain("secret server detail");
                });
    }
}
