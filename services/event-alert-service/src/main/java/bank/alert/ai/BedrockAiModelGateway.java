package bank.alert.ai;

import bank.alert.config.AiProperties;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ModelNotReadyException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.model.OutputFormatType;
import software.amazon.awssdk.services.bedrockruntime.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.Collectors;

public final class BedrockAiModelGateway implements AiModelGateway {
    private final BedrockRuntimeClient client;
    private final AiProperties properties;

    public BedrockAiModelGateway(BedrockRuntimeClient client, AiProperties properties) {
        this.client = Objects.requireNonNull(client);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public String invoke(AiModelRequest request, Duration timeout) {
        return invokeMeasured(request, timeout).outputJson();
    }

    @Override
    public AiModelResponse invokeMeasured(AiModelRequest request, Duration timeout) {
        if (!properties.enabled()) {
            throw failure("MODEL_DISABLED", false);
        }
        if (!properties.model().equals(request.model())) {
            throw failure("MODEL_NOT_ALLOWED", false);
        }

        ConverseRequest converseRequest = ConverseRequest.builder()
                .modelId(request.model())
                .system(SystemContentBlock.fromText(systemText(request)))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(request.publicInputJson()))
                        .build())
                .inferenceConfig(config -> config
                        .maxTokens(request.maximumOutputTokens())
                        .temperature(0.0F))
                .outputConfig(config -> config.textFormat(format -> format
                        .type(OutputFormatType.JSON_SCHEMA)
                        .structure(structure -> structure.jsonSchema(schema -> schema
                                .name(schemaName(request))
                                .description("Closed JSON output for " + request.operation())
                                .schema(request.outputSchema())))))
                .overrideConfiguration(config -> config
                        .apiCallTimeout(timeout)
                        .apiCallAttemptTimeout(timeout))
                .build();

        try {
            ConverseResponse response = client.converse(converseRequest);
            if (response.output() == null || response.output().message() == null) {
                throw failure("EMPTY_MODEL_RESPONSE", true);
            }
            String output = response.output().message().content().stream()
                    .map(ContentBlock::text)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining());
            if (output.isBlank()) throw failure("EMPTY_MODEL_RESPONSE", true);
            int inputTokens = response.usage() == null || response.usage().inputTokens() == null
                    ? 0 : response.usage().inputTokens();
            int outputTokens = response.usage() == null || response.usage().outputTokens() == null
                    ? 0 : response.usage().outputTokens();
            long latencyMillis = response.metrics() == null || response.metrics().latencyMs() == null
                    ? 0 : response.metrics().latencyMs();
            return new AiModelResponse(output, inputTokens, outputTokens, latencyMillis,
                    response.stopReasonAsString());
        } catch (ModelTimeoutException exception) {
            throw failure("MODEL_TIMEOUT", true);
        } catch (ThrottlingException exception) {
            throw failure("MODEL_THROTTLED", true);
        } catch (ModelNotReadyException exception) {
            throw failure("MODEL_NOT_READY", true);
        } catch (ServiceUnavailableException exception) {
            throw failure("MODEL_UNAVAILABLE", true);
        } catch (AccessDeniedException exception) {
            throw failure("MODEL_ACCESS_DENIED", false);
        } catch (ResourceNotFoundException exception) {
            throw failure("MODEL_NOT_FOUND", false);
        } catch (ValidationException exception) {
            throw failure("MODEL_REQUEST_REJECTED", false);
        } catch (SdkClientException exception) {
            throw failure("MODEL_CLIENT_FAILURE", true);
        }
    }

    private static String systemText(AiModelRequest request) {
        return request.systemInstruction() + " Operation: " + request.operation()
                + ". Prompt version: " + request.promptVersion()
                + ". Return the configured structured JSON output only.";
    }

    private static String schemaName(AiModelRequest request) {
        return request.operation().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private static AiProviderException failure(String code, boolean retryable) {
        return new AiProviderException(code, retryable, "model invocation failed safely");
    }
}
