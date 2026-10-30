package bank.clienthealth.explanation;

import bank.clienthealth.explanation.ExplanationMetricsPort.Event;
import bank.clienthealth.explanation.ExplanationMetricsPort.Type;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
                String output = response.output().message().content().stream()
                        .map(ContentBlock::text).filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.joining());
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

    private ConverseRequest converseRequest(String input) {
        return ConverseRequest.builder().modelId(properties.modelId())
                .system(SystemContentBlock.fromText(SYSTEM))
                .messages(Message.builder().role(ConversationRole.USER)
                        .content(ContentBlock.fromText(input)).build())
                .inferenceConfig(config -> config.maxTokens(properties.maxOutputTokens()).temperature(0.0F))
                .outputConfig(config -> config.textFormat(format -> format.type(OutputFormatType.JSON_SCHEMA)
                        .structure(structure -> structure.jsonSchema(schema -> schema
                                .name("relationship-health-explanation").schema(OUTPUT_SCHEMA)))))
                .overrideConfiguration(config -> config.apiCallTimeout(properties.timeout())
                        .apiCallAttemptTimeout(properties.timeout())).build();
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
