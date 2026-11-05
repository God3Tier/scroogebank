package bank.alert.ai;

import bank.alert.config.AiProperties;
import bank.alert.config.AnthropicAiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Anthropic gateway against a local stand-in for the Messages API: request shape, parsing and safe failures. */
class AnthropicAiModelGatewayTest {

    private static final String MODEL = "claude-sonnet-5-5";
    private static final String KEY = "sk-test-not-a-real-key";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<com.sun.net.httpserver.Headers> headers = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            headers.set(exchange.getRequestHeaders());
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AnthropicAiModelGateway gateway(boolean enabled, String key) {
        AiProperties ai = new AiProperties(enabled, MODEL, "alert-public-v1", 12_000, 8_000, 1_200, 2,
                Duration.ofSeconds(10), new java.math.BigDecimal("0.60"));
        AnthropicAiProperties anthropic = new AnthropicAiProperties(key,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages", null);
        return new AnthropicAiModelGateway(HttpClient.newHttpClient(), ai, anthropic);
    }

    private static AiModelRequest request() {
        return new AiModelRequest("EVENT_CLASSIFICATION", MODEL, "alert-public-v1", "Classify public news.",
                "{\"headline\":\"DBS reports higher profit\"}",
                "{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\"}},\"required\":[\"label\"]}", 500);
    }

    private static String toolReply(String inputJson) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + MODEL + "\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"},{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"event-classification\","
                + "\"input\":" + inputJson + "}],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":321,\"output_tokens\":45}}";
    }

    @Test
    void sendsTheKeyAVersionAndAToolWithTheOutputSchema() throws Exception {
        reply = toolReply("{\"label\":\"EARNINGS\"}");

        gateway(true, KEY).invoke(request(), Duration.ofSeconds(10));

        assertThat(headers.get().getFirst("x-api-key")).isEqualTo(KEY);
        assertThat(headers.get().getFirst("anthropic-version")).isEqualTo("2023-06-01");
        JsonNode sent = MAPPER.readTree(body.get());
        assertThat(sent.get("model").asText()).isEqualTo(MODEL);
        assertThat(sent.get("max_tokens").asInt()).isEqualTo(500);
        assertThat(sent.get("system").asText()).contains("Classify public news.").contains("EVENT_CLASSIFICATION");
        assertThat(sent.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(sent.get("messages").get(0).get("content").asText()).contains("DBS reports higher profit");
        assertThat(sent.get("tools").get(0).get("name").asText()).isEqualTo("event-classification");
        assertThat(sent.get("tools").get(0).get("input_schema").get("required").get(0).asText()).isEqualTo("label");
        // Newer Claude models refuse a forced tool choice, so none is sent; the model is told to call the tool.
        assertThat(sent.has("tool_choice")).isFalse();
        assertThat(sent.get("system").asText()).contains("calling the tool event-classification exactly once");
    }

    @Test
    void returnsTheToolInputAsTheStructuredOutputWithUsage() {
        reply = toolReply("{\"label\":\"EARNINGS\"}");

        AiModelResponse response = gateway(true, KEY).invokeMeasured(request(), Duration.ofSeconds(10));

        assertThat(response.outputJson()).isEqualTo("{\"label\":\"EARNINGS\"}");
        assertThat(response.inputTokens()).isEqualTo(321);
        assertThat(response.outputTokens()).isEqualTo(45);
        assertThat(response.stopReason()).isEqualTo("tool_use");
    }

    @Test
    void aReplyWithoutAToolCallIsAnEmptyResponseThatMayBeRetried() {
        reply = "{\"content\":[{\"type\":\"text\",\"text\":\"I cannot\"}],\"stop_reason\":\"end_turn\",\"usage\":{}}";

        assertThatThrownBy(() -> gateway(true, KEY).invoke(request(), Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    assertThat(e.code()).isEqualTo("EMPTY_MODEL_RESPONSE");
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void mapsHttpFailuresToStableCodesAndNeverLeaksTheKey() {
        record Case(int status, String code, boolean retryable) {}
        for (Case c : new Case[] {new Case(401, "MODEL_ACCESS_DENIED", false), new Case(403, "MODEL_ACCESS_DENIED", false),
                new Case(404, "MODEL_NOT_FOUND", false), new Case(429, "MODEL_THROTTLED", true),
                new Case(400, "MODEL_REQUEST_REJECTED", false), new Case(500, "MODEL_UNAVAILABLE", true),
                new Case(529, "MODEL_UNAVAILABLE", true)}) {
            status = c.status();
            reply = "{\"error\":{\"message\":\"echo " + KEY + "\"}}";

            assertThatThrownBy(() -> gateway(true, KEY).invoke(request(), Duration.ofSeconds(10)))
                    .isInstanceOfSatisfying(AiProviderException.class, e -> {
                        assertThat(e.code()).isEqualTo(c.code());
                        assertThat(e.retryable()).isEqualTo(c.retryable());
                        assertThat(e.getMessage()).doesNotContain(KEY);
                    });
        }
    }

    @Test
    void refusesWhenSwitchedOffWhenTheModelIsNotTheConfiguredOneAndWhenThereIsNoKey() {
        assertThatThrownBy(() -> gateway(false, KEY).invoke(request(), Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.code()).isEqualTo("MODEL_DISABLED"));
        AiModelRequest other = new AiModelRequest("EVENT_CLASSIFICATION", "another-model", "alert-public-v1", "x", "{}", "{}", 10);
        assertThatThrownBy(() -> gateway(true, KEY).invoke(other, Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.code()).isEqualTo("MODEL_NOT_ALLOWED"));
        assertThatThrownBy(() -> gateway(true, "").invoke(request(), Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.code()).isEqualTo("MODEL_ACCESS_DENIED"));
        assertThat(body.get()).isNull();
    }

    @Test
    void aBadOutputSchemaIsRefusedBeforeAnythingIsSent() {
        AiModelRequest bad = new AiModelRequest("EVENT_CLASSIFICATION", MODEL, "alert-public-v1", "x", "{}", "{not json", 10);

        assertThatThrownBy(() -> gateway(true, KEY).invoke(bad, Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.code()).isEqualTo("INVALID_TOOL_SCHEMA"));
        assertThat(body.get()).isNull();
    }

    @Test
    void thePropertiesNeverPrintTheKey() {
        assertThat(new AnthropicAiProperties(KEY, null, null).toString()).doesNotContain(KEY).contains("apiKey=set");
        assertThat(new AnthropicAiProperties("", null, null).toString()).contains("apiKey=missing");
        assertThat(new AnthropicAiProperties(KEY, null, null).endpoint()).isEqualTo("https://api.anthropic.com/v1/messages");
    }
}
