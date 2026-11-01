package bank.engagement.drafting;

import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Bedrock Runtime {@code Converse} in Singapore with Nova Lite's
 * Asia-Pacific inference profile (ADR-0067). The reply is plain text that
 * the safety screen parses as JSON; Nova rejects Converse structured output
 * ({@code outputConfig}), so it is not used.
 */
public class BedrockDraftModelGateway implements DraftModelGateway {

    private final BedrockRuntimeClient client;
    private final AiProperties properties;

    public BedrockDraftModelGateway(BedrockRuntimeClient client, AiProperties properties) {
        this.client = Objects.requireNonNull(client);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public ModelReply invoke(String systemPrompt, String userInputJson) {
        ConverseRequest request = ConverseRequest.builder()
                .modelId(properties.modelId())
                .system(SystemContentBlock.fromText(systemPrompt))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(userInputJson))
                        .build())
                .inferenceConfig(config -> config
                        .temperature(properties.temperature())
                        .maxTokens(properties.maxOutputTokens()))
                .overrideConfiguration(config -> config
                        .apiCallTimeout(properties.timeout())
                        .apiCallAttemptTimeout(properties.timeout()))
                .build();
        try {
            ConverseResponse response = client.converse(request);
            if (response.output() == null || response.output().message() == null) {
                throw new DraftModelException(GeneratedDraft.MODEL_ERROR);
            }
            String text = response.output().message().content().stream()
                    .map(ContentBlock::text)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining());
            long input = response.usage() == null || response.usage().inputTokens() == null
                    ? 0 : response.usage().inputTokens();
            long output = response.usage() == null || response.usage().outputTokens() == null
                    ? 0 : response.usage().outputTokens();
            return new ModelReply(text, input, output);
        } catch (ModelTimeoutException | ApiCallTimeoutException | ApiCallAttemptTimeoutException ex) {
            throw new DraftModelException(GeneratedDraft.MODEL_TIMEOUT);
        } catch (SdkException ex) {
            throw new DraftModelException(GeneratedDraft.MODEL_ERROR);
        }
    }
}
