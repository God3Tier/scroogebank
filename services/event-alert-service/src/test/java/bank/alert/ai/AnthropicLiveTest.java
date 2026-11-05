package bank.alert.ai;

import bank.alert.config.AiProperties;
import bank.alert.config.AnthropicAiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One real call to the Anthropic API (ADR-0083), a few hundred tokens. Runs only when ALERT_ANTHROPIC_API_KEY is
 * set, with ALERT_AI_MODEL naming the model. Not part of the normal build.
 */
@EnabledIfEnvironmentVariable(named = "ALERT_ANTHROPIC_API_KEY", matches = ".+")
class AnthropicLiveTest {

    @Test
    void theModelReturnsTheClosedStructuredOutput() throws Exception {
        String model = System.getenv().getOrDefault("ALERT_AI_MODEL", "claude-sonnet-5-5");
        AiProperties ai = new AiProperties(true, model, "alert-public-v1", 12_000, 8_000, 1_200, 2,
                Duration.ofSeconds(30), new BigDecimal("0.60"));
        AnthropicAiProperties anthropic = new AnthropicAiProperties(System.getenv("ALERT_ANTHROPIC_API_KEY"), null, null);
        AnthropicAiModelGateway gateway = new AnthropicAiModelGateway(HttpClient.newHttpClient(), ai, anthropic);

        AiModelResponse response;
        try {
            response = gateway.invokeMeasured(new AiModelRequest("EVENT_CLASSIFICATION", model, "alert-public-v1",
                "Classify the public news headline into one event type.",
                "{\"headline\":\"DBS reports record second-quarter profit and raises 2026 guidance\"}",
                "{\"type\":\"object\",\"properties\":{\"eventType\":{\"type\":\"string\",\"enum\":[\"EARNINGS\",\"MERGER\",\"OTHER\"]}},"
                        + "\"required\":[\"eventType\"],\"additionalProperties\":false}", 200), Duration.ofSeconds(30));
        } catch (AiProviderException failed) {
            System.out.println("LIVE anthropic failed code=" + failed.code() + " retryable=" + failed.retryable());
            throw failed;
        }

        System.out.println("LIVE anthropic " + model + ": " + response.outputJson() + " tokens in/out "
                + response.inputTokens() + "/" + response.outputTokens() + " " + response.latencyMillis() + " ms");
        JsonNode output = new ObjectMapper().readTree(response.outputJson());
        assertThat(output.get("eventType").asText()).isEqualTo("EARNINGS");
        assertThat(response.inputTokens()).isPositive();
    }
}
