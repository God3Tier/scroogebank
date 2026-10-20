package bank.alert.ingestion;

import bank.alert.config.AgentCoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the client against a fake MCP gateway on localhost. It checks the request
 * the real gateway would receive (IAM signature, tool discovery, filters) and how
 * the replies are read. It does not prove the real service behaves this way:
 * that needs the live smoke test (ADR-0049).
 */
class HttpAgentCoreGatewayClientTest {

    private static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile Function<JsonNode, Reply> handler;

    record Reply(int status, String contentType, String body) {
        static Reply json(String body) {
            return new Reply(200, "application/json", body);
        }
    }

    @BeforeEach
    void startGateway() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        handler = request -> "tools/list".equals(request.path("method").asText())
                ? Reply.json(tools("WebSearch___search"))
                : Reply.json(results("""
                        {"id":"r-1","title":"SYN1 reports earnings","url":"https://www.sgx.com/news/1",
                         "publishedDate":"2026-10-20T01:30:00Z","snippet":"Synthetic Issuer One reported quarterly earnings."}"""));
    }

    @AfterEach
    void stopGateway() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode request = MAPPER.readTree(body);
        requests.add(request);
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        Reply reply = handler.apply(request);
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", reply.contentType());
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String tools(String... names) {
        List<String> items = new ArrayList<>();
        for (String name : names) items.add("{\"name\":\"" + name + "\"}");
        return "{\"jsonrpc\":\"2.0\",\"id\":\"list-tools\",\"result\":{\"tools\":[" + String.join(",", items) + "]}}";
    }

    private static String results(String... items) {
        String text = "{\"results\":[" + String.join(",", items) + "]}";
        return "{\"jsonrpc\":\"2.0\",\"id\":\"search\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":"
                + quote(text) + "}]}}";
    }

    private static String quote(String text) {
        try {
            return MAPPER.writeValueAsString(text);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private HttpAgentCoreGatewayClient client() {
        return new HttpAgentCoreGatewayClient(
                new AgentCoreProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                        "ap-northeast-1", Duration.ofSeconds(5)),
                MAPPER,
                StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secretexample")),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AgentCoreSearchPayload payload() {
        return new AgentCoreSearchPayload("1.2.0", "SYN1 earnings", List.of("sgx.com", "mas.gov.sg"),
                "2026-10-20T00:00:00Z", "2026-10-20T04:00:00Z", 5);
    }

    @Test
    void signsEveryCallWithIamForTheAgentCoreService() {
        client().invoke(payload());

        assertThat(authorizations).hasSize(2).allSatisfy(header -> assertThat(header)
                .startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261020/ap-northeast-1/bedrock-agentcore/aws4_request")
                .contains("SignedHeaders=").contains("Signature="));
    }

    @Test
    void findsTheSearchToolOnceAndSendsOnlyThePublicFilters() {
        HttpAgentCoreGatewayClient client = client();

        client.invoke(payload());
        client.invoke(payload());

        assertThat(requests).extracting(request -> request.path("method").asText())
                .containsExactly("tools/list", "tools/call", "tools/call");
        JsonNode call = requests.get(1).path("params");
        assertThat(call.path("name").asText()).isEqualTo("WebSearch___search");
        JsonNode arguments = call.path("arguments");
        assertThat(arguments.path("query").asText()).isEqualTo("SYN1 earnings");
        assertThat(arguments.path("maxResults").asInt()).isEqualTo(5);
        assertThat(arguments.path("filters").path("domainFilter").path("include"))
                .extracting(JsonNode::asText).containsExactly("sgx.com", "mas.gov.sg");
        assertThat(arguments.path("filters").path("publishedDateFilter").path("from").asText())
                .isEqualTo("2026-10-20T00:00:00Z");
        assertThat(arguments.path("filters").path("publishedDateFilter").path("to").asText())
                .isEqualTo("2026-10-20T04:00:00Z");
    }

    @Test
    void mapsResultsWithTheSnippetKeptAsTheExcerpt() {
        List<PublicSearchResult> found = client().invoke(payload()).results();

        assertThat(found).singleElement().satisfies(result -> {
            assertThat(result.providerResultId()).isEqualTo("r-1");
            assertThat(result.url()).isEqualTo("https://www.sgx.com/news/1");
            assertThat(result.title()).isEqualTo("SYN1 reports earnings");
            assertThat(result.publishedAt()).isEqualTo(Instant.parse("2026-10-20T01:30:00Z"));
            assertThat(result.retrievedAt()).isEqualTo(NOW);
            assertThat(result.contentHash()).matches("[0-9a-f]{64}");
            assertThat(result.snippet()).isEqualTo("Synthetic Issuer One reported quarterly earnings.");
        });
    }

    @Test
    void readsAnEventStreamReplyAndSkipsMalformedResults() {
        handler = request -> "tools/list".equals(request.path("method").asText())
                ? Reply.json(tools("WebSearch___search"))
                : new Reply(200, "text/event-stream", "event: message\ndata: " + results(
                        "{\"title\":\"no url\"}",
                        "{\"url\":\"https://www.sgx.com/news/2\",\"title\":\"Valid\"}") + "\n\n");

        assertThat(client().invoke(payload()).results())
                .singleElement().satisfies(result -> {
                    assertThat(result.title()).isEqualTo("Valid");
                    assertThat(result.snippet()).isNull();
                    assertThat(result.publishedAt()).isNull();
                });
    }

    @Test
    void truncatesALongSnippetAndCleansItsWhitespace() {
        String longText = "word ".repeat(400);
        handler = request -> "tools/list".equals(request.path("method").asText())
                ? Reply.json(tools("WebSearch___search"))
                : Reply.json(results("{\"url\":\"https://www.sgx.com/n\",\"title\":\"T\",\"summary\":"
                        + quote("a\n\n  b\t" + longText) + "}"));

        PublicSearchResult result = client().invoke(payload()).results().getFirst();

        assertThat(result.snippet()).hasSize(PublicSearchResult.MAX_SNIPPET).startsWith("a b word word");
    }

    @Test
    void classifiesFailuresForTheRetryPolicy() {
        handler = request -> new Reply(403, "application/json", "{}");
        assertThatThrownBy(() -> client().invoke(payload())).isInstanceOfSatisfying(SearchProviderException.class,
                failure -> {
                    assertThat(failure.code()).isEqualTo("PROVIDER_ACCESS_DENIED");
                    assertThat(failure.retryable()).isFalse();
                });

        handler = request -> new Reply(503, "application/json", "{}");
        assertThatThrownBy(() -> client().invoke(payload())).isInstanceOfSatisfying(SearchProviderException.class,
                failure -> {
                    assertThat(failure.code()).isEqualTo("PROVIDER_UNAVAILABLE");
                    assertThat(failure.retryable()).isTrue();
                });

        handler = request -> new Reply(400, "application/json", "{}");
        assertThatThrownBy(() -> client().invoke(payload())).isInstanceOfSatisfying(SearchProviderException.class,
                failure -> assertThat(failure.code()).isEqualTo("PROVIDER_REJECTED"));
    }

    @Test
    void anMcpErrorOrNonJsonReplyIsNotRetried() {
        handler = request -> "tools/list".equals(request.path("method").asText())
                ? Reply.json(tools("WebSearch___search"))
                : Reply.json("{\"jsonrpc\":\"2.0\",\"id\":\"search\",\"error\":{\"code\":-32000,\"message\":\"boom\"}}");
        assertThatThrownBy(() -> client().invoke(payload())).isInstanceOfSatisfying(SearchProviderException.class,
                failure -> {
                    assertThat(failure.code()).isEqualTo("PROVIDER_ERROR");
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getMessage()).doesNotContain("boom");
                });

        handler = request -> Reply.json("not json");
        assertThatThrownBy(() -> client().invoke(payload())).isInstanceOfSatisfying(SearchProviderException.class,
                failure -> assertThat(failure.code()).isEqualTo("INVALID_PROVIDER_RESPONSE"));
    }

    @Test
    void refusesToSearchWhenTheGatewayDoesNotExposeExactlyOneSearchTool() {
        handler = request -> Reply.json(tools("WebSearch___a", "WebSearch___b"));

        assertThatThrownBy(() -> client().invoke(payload())).isInstanceOfSatisfying(SearchProviderException.class,
                failure -> assertThat(failure.code()).isEqualTo("PROVIDER_TOOL_NOT_FOUND"));
        assertThat(requests).hasSize(1);
    }

    @Test
    void anUnreachableGatewayIsARetryableOutage() {
        server.stop(0);

        assertThatThrownBy(() -> client().invoke(payload())).isInstanceOfSatisfying(SearchProviderException.class,
                failure -> {
                    assertThat(failure.code()).isEqualTo("PROVIDER_UNAVAILABLE");
                    assertThat(failure.retryable()).isTrue();
                });
    }

    @Test
    void worksAsTheProviderBehindTheDomainAndResultBounds() {
        AgentCoreSearchProvider provider = new AgentCoreSearchProvider(client(), List.of("sgx.com", "mas.gov.sg"));

        List<PublicSearchResult> found = provider.search(new PublicSearchRequest("SYN1 earnings",
                List.of("sgx.com"), Instant.parse("2026-10-20T00:00:00Z"), NOW, 5));

        assertThat(found).hasSize(1);
        assertThat(requests.get(1).path("params").path("arguments").path("filters")
                .path("domainFilter").path("include")).extracting(JsonNode::asText).containsExactly("sgx.com");
    }
}
