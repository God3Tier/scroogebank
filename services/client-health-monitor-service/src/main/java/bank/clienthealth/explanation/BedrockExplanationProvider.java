package bank.clienthealth.explanation;

import bank.clienthealth.explanation.ExplanationMetricsPort.Event;
import bank.clienthealth.explanation.ExplanationMetricsPort.Type;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

public final class BedrockExplanationProvider implements ExplanationProvider {
    static final String OUTPUT_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["schemaVersion","summary","suggestedReviewStep","evidenceCodes","disclaimer"],
             "properties":{"schemaVersion":{"const":"1"},"summary":{"type":"string"},
             "suggestedReviewStep":{"type":"string"},"evidenceCodes":{"type":"array","minItems":1,
             "items":{"enum":["RECURRING_CREDIT_INTERRUPTION","TRANSACTION_ACTIVITY_DECLINE","SUSTAINED_NET_OUTFLOW"]}},
             "disclaimer":{"const":"Human review required"}}}
            """;
    // Nova rejects tool names containing hyphens with ModelErrorException (ADR-0067).
    static final String TOOL_NAME = "relationship_health_explanation";
    private static final String SYSTEM = "Explain only the supplied verified relationship-health facts. "
            + "Use qualitative wording and do not include any numeric values. "
            + "Do not add facts, identifiers, advice, products, fraud, hardship, distress or attrition claims.";
    private final BedrockRuntimeClient client;
    private final ClientHealthAiProperties properties;
    private final ExplanationSafetyValidator validator;
    private final ExplanationMetricsPort metrics;
    private final ObjectMapper mapper;

    public BedrockExplanationProvider(BedrockRuntimeClient client, ClientHealthAiProperties properties,
            ExplanationSafetyValidator validator, ExplanationMetricsPort metrics, ObjectMapper mapper) {
        this.client = client; this.properties = properties; this.validator = validator;
        this.metrics = metrics; this.mapper = mapper;
    }

    @Override
    public ExplanationResult explain(ExplanationRequest request) {
        final String input;
        try { input = mapper.writeValueAsString(request); }
        catch (JsonProcessingException impossible) { throw new ProviderException("INPUT_INVALID", 0); }
        if (input.length() > properties.maxInputCharacters()) {
            throw new ProviderException("INPUT_TOO_LONG", 0);
        }
        final ConverseRequest providerRequest;
        try {
            providerRequest = converseRequest(input);
        } catch (RuntimeException invalid) {
            throw new ProviderException("INPUT_INVALID", 0);
        }
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            long started = System.nanoTime();
            try {
                ConverseResponse response = client.converse(providerRequest);
                String output = toolInput(response);
                int in = response.usage() == null || response.usage().inputTokens() == null
                        ? 0 : response.usage().inputTokens();
                int out = response.usage() == null || response.usage().outputTokens() == null
                        ? 0 : response.usage().outputTokens();
                long latency = response.metrics() == null || response.metrics().latencyMs() == null
                        ? 0 : response.metrics().latencyMs();
                ExplanationResult result;
                try {
                    result = validator.validate(output, request, properties.modelId());
                } catch (ExplanationSafetyValidator.UnsafeExplanationException unsafe) {
                    metrics.record(new Event(Type.PROVIDER_FAILURE, "INVALID_OUTPUT", properties.modelId(),
                            attempt, in, out, latency, estimatedCost(in, out)));
                    throw unsafe;
                }
                metrics.record(new Event(Type.MODEL_CALL, response.stopReasonAsString(), properties.modelId(),
                        attempt, in, out, latency, estimatedCost(in, out)));
                return result;
            } catch (ExplanationSafetyValidator.UnsafeExplanationException unsafe) {
                throw unsafe;
            } catch (RuntimeException failure) {
                metrics.record(new Event(Type.PROVIDER_FAILURE, code(failure), properties.modelId(),
                        attempt, 0, 0, elapsedMillis(started), null));
                boolean retryable = retryable(failure);
                if (!retryable || attempt == properties.maxAttempts()) {
                    throw new ProviderException(code(failure), attempt);
                }
            }
        }
        throw new ProviderException("MODEL_FAILED", properties.maxAttempts());
    }

    // Nova does not support outputConfig JSON schemas, so the schema is a forced tool (ADR-0067).
    private ConverseRequest converseRequest(String input) {
        final Document schema;
        try { schema = toDocument(mapper.readTree(OUTPUT_SCHEMA)); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
        return ConverseRequest.builder().modelId(properties.modelId())
                .system(SystemContentBlock.fromText(SYSTEM))
                .messages(Message.builder().role(ConversationRole.USER)
                        .content(ContentBlock.fromText(input)).build())
                .inferenceConfig(config -> config.maxTokens(properties.maxOutputTokens()).temperature(0.0F))
                .toolConfig(tools -> tools
                        .tools(Tool.fromToolSpec(spec -> spec.name(TOOL_NAME)
                                .description("Closed JSON output for the relationship-health explanation")
                                .inputSchema(ToolInputSchema.fromJson(schema))))
                        .toolChoice(ToolChoice.fromTool(choice -> choice.name(TOOL_NAME))))
                .overrideConfiguration(config -> config.apiCallTimeout(properties.timeout())
                        .apiCallAttemptTimeout(properties.timeout())).build();
    }

    /** The forced tool's input as JSON, or blank (rejected by the validator) when the model used no tool. */
    private String toolInput(ConverseResponse response) {
        if (response.output() == null || response.output().message() == null) return "";
        ToolUseBlock toolUse = response.output().message().content().stream()
                .map(ContentBlock::toolUse).filter(Objects::nonNull).findFirst().orElse(null);
        if (toolUse == null || toolUse.input() == null) return "";
        try { return mapper.writeValueAsString(fromDocument(toolUse.input())); }
        catch (JsonProcessingException invalid) { return ""; }
    }

    private static Document toDocument(JsonNode node) {
        if (node.isObject()) {
            Document.MapBuilder builder = Document.mapBuilder();
            node.properties().forEach(entry -> builder.putDocument(entry.getKey(), toDocument(entry.getValue())));
            return builder.build();
        }
        if (node.isArray()) {
            Document.ListBuilder builder = Document.listBuilder();
            node.forEach(element -> builder.addDocument(toDocument(element)));
            return builder.build();
        }
        if (node.isTextual()) return Document.fromString(node.textValue());
        if (node.isBoolean()) return Document.fromBoolean(node.booleanValue());
        if (node.isNumber()) return Document.fromNumber(node.asText());
        return Document.fromNull();
    }

    private static Object fromDocument(Document document) {
        if (document.isMap()) {
            Map<String, Object> result = new LinkedHashMap<>();
            document.asMap().forEach((key, value) -> result.put(key, fromDocument(value)));
            return result;
        }
        if (document.isList()) return document.asList().stream().map(BedrockExplanationProvider::fromDocument).toList();
        if (document.isString()) return document.asString();
        if (document.isBoolean()) return document.asBoolean();
        if (document.isNumber()) return document.asNumber().bigDecimalValue();
        return null;
    }

    private BigDecimal estimatedCost(int input, int output) {
        if (properties.inputCostPerMillionTokens() == null
                || properties.outputCostPerMillionTokens() == null) return null;
        return properties.inputCostPerMillionTokens().multiply(BigDecimal.valueOf(input))
                .add(properties.outputCostPerMillionTokens().multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_UP);
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static boolean retryable(RuntimeException failure) {
        return failure instanceof ModelTimeoutException || failure instanceof ThrottlingException
                || failure instanceof ModelNotReadyException || failure instanceof ServiceUnavailableException
                || failure instanceof SdkClientException;
    }

    private static String code(RuntimeException failure) {
        if (failure instanceof ModelTimeoutException) return "MODEL_TIMEOUT";
        if (failure instanceof ThrottlingException) return "MODEL_THROTTLED";
        if (failure instanceof ModelNotReadyException) return "MODEL_NOT_READY";
        if (failure instanceof ServiceUnavailableException) return "MODEL_UNAVAILABLE";
        if (failure instanceof AccessDeniedException) return "MODEL_ACCESS_DENIED";
        if (failure instanceof ValidationException) return "MODEL_REQUEST_REJECTED";
        return "MODEL_CLIENT_FAILURE";
    }

    public static final class ProviderException extends RuntimeException {
        private final String code; private final int attempts;
        ProviderException(String code, int attempts) { super(code); this.code = code; this.attempts = attempts; }
        public String code() { return code; }
        public int attempts() { return attempts; }
    }
}
