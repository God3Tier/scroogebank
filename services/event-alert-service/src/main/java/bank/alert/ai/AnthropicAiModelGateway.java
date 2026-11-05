package bank.alert.ai;

import bank.alert.config.AiProperties;
import bank.alert.config.AnthropicAiProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Calls the Anthropic Messages API with the same closed, structured output as the Bedrock gateway: one
 * tool whose input is the output schema, which the model is told to call (ADR-0083). Newer Claude models do not
 * accept a forced tool choice, so a reply without the tool call is an empty response that may be retried. For developer machines only; only public text
 * is sent, exactly as for Bedrock. The key is sent in a header and never logged or put in an error.
 */
public final class AnthropicAiModelGateway implements AiModelGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final AiProperties properties;
    private final AnthropicAiProperties anthropic;

    public AnthropicAiModelGateway(HttpClient http, AiProperties properties, AnthropicAiProperties anthropic) {
        this.http = Objects.requireNonNull(http);
        this.properties = Objects.requireNonNull(properties);
        this.anthropic = Objects.requireNonNull(anthropic);
    }

    @Override
    public String invoke(AiModelRequest request, Duration timeout) {
        return invokeMeasured(request, timeout).outputJson();
    }

    @Override
    public AiModelResponse invokeMeasured(AiModelRequest request, Duration timeout) {
        if (!properties.enabled()) throw failure("MODEL_DISABLED", false);
        if (!properties.model().equals(request.model())) throw failure("MODEL_NOT_ALLOWED", false);
        if (!anthropic.hasKey()) throw failure("MODEL_ACCESS_DENIED", false);

        String body = requestBody(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(anthropic.endpoint()))
                .timeout(timeout)
                .header("content-type", "application/json")
                .header("x-api-key", anthropic.apiKey())
                .header("anthropic-version", anthropic.version())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException exception) {
            throw failure("MODEL_TIMEOUT", true);
        } catch (IOException exception) {
            throw failure("MODEL_CLIENT_FAILURE", true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("MODEL_CLIENT_FAILURE", true);
        }
        long latencyMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        int status = response.statusCode();
        if (status == 401 || status == 403) throw failure("MODEL_ACCESS_DENIED", false);
        if (status == 404) throw failure("MODEL_NOT_FOUND", false);
        if (status == 429) throw failure("MODEL_THROTTLED", true);
        if (status == 400 || status == 413 || status == 422) throw failure("MODEL_REQUEST_REJECTED", false);
        if (status >= 500) throw failure("MODEL_UNAVAILABLE", true);
        if (status != 200) throw failure("MODEL_REQUEST_REJECTED", false);
        return parse(response.body(), latencyMillis);
    }

    private String requestBody(AiModelRequest request) {
        String toolName = request.operation().toLowerCase(Locale.ROOT).replace('_', '-');
        JsonNode schema;
        try {
            schema = MAPPER.readTree(request.outputSchema());
        } catch (JsonProcessingException exception) {
            throw failure("INVALID_TOOL_SCHEMA", false);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", request.model());
        root.put("max_tokens", request.maximumOutputTokens());
        root.put("system", request.systemInstruction() + " Operation: " + request.operation()
                + ". Prompt version: " + request.promptVersion() + ". Return the answer only by calling the tool "
                + toolName + " exactly once; write no other text.");
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "user").put("content", request.publicInputJson());
        ObjectNode tool = root.putArray("tools").addObject();
        tool.put("name", toolName);
        tool.put("description", "Closed JSON output for " + request.operation());
        tool.set("input_schema", schema);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw failure("INVALID_TOOL_SCHEMA", false);
        }
    }

    private static AiModelResponse parse(String body, long latencyMillis) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException exception) {
            throw failure("EMPTY_MODEL_RESPONSE", true);
        }
        JsonNode toolInput = null;
        for (JsonNode block : root.path("content")) {
            if ("tool_use".equals(block.path("type").asText()) && block.has("input")) {
                toolInput = block.get("input");
                break;
            }
        }
        if (toolInput == null || !toolInput.isObject()) throw failure("EMPTY_MODEL_RESPONSE", true);
        String output;
        try {
            output = MAPPER.writeValueAsString(toolInput);
        } catch (JsonProcessingException exception) {
            throw failure("INVALID_TOOL_SCHEMA", false);
        }
        int inputTokens = Math.max(0, root.path("usage").path("input_tokens").asInt(0));
        int outputTokens = Math.max(0, root.path("usage").path("output_tokens").asInt(0));
        return new AiModelResponse(output, inputTokens, outputTokens, latencyMillis, root.path("stop_reason").asText("UNKNOWN"));
    }

    private static AiProviderException failure(String code, boolean retryable) {
        return new AiProviderException(code, retryable, "model invocation failed safely");
    }
}
