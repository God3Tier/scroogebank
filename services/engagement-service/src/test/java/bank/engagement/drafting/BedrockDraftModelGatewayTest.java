package bank.engagement.drafting;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BedrockDraftModelGatewayTest {

    private final BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
    private final AiProperties properties = new AiProperties(true, null, null, null, null, null, null, null);
    private final BedrockDraftModelGateway gateway = new BedrockDraftModelGateway(client, properties);

    @Test
    void callsConverseWithTheProfileAndTheAdrSettings() {
        when(client.converse(any(ConverseRequest.class))).thenReturn(ConverseResponse.builder()
                .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText("{\"subject\":\"a\",\"body\":\"b\"}")).build()))
                .usage(TokenUsage.builder().inputTokens(120).outputTokens(80).totalTokens(200).build())
                .build());

        DraftModelGateway.ModelReply reply = gateway.invoke("system", "{\"purpose\":\"CHECK_IN\"}");

        assertThat(reply.text()).isEqualTo("{\"subject\":\"a\",\"body\":\"b\"}");
        assertThat(reply.inputTokens()).isEqualTo(120);
        assertThat(reply.outputTokens()).isEqualTo(80);
        ArgumentCaptor<ConverseRequest> request = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(client).converse(request.capture());
        assertThat(request.getValue().modelId()).isEqualTo("apac.amazon.nova-lite-v1:0");
        assertThat(request.getValue().inferenceConfig().temperature()).isEqualTo(0.3F);
        assertThat(request.getValue().inferenceConfig().maxTokens()).isEqualTo(600);
        assertThat(request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout())
                .contains(Duration.ofSeconds(15));
    }

    @Test
    void mapsFailuresToSourceReasons() {
        when(client.converse(any(ConverseRequest.class))).thenThrow(ApiCallTimeoutException.create(15_000));
        assertThatThrownBy(() -> gateway.invoke("s", "{}"))
                .isInstanceOfSatisfying(DraftModelException.class,
                        ex -> assertThat(ex.reason()).isEqualTo("model_timeout"));

        when(client.converse(any(ConverseRequest.class))).thenThrow(AccessDeniedException.builder().build());
        assertThatThrownBy(() -> gateway.invoke("s", "{}"))
                .isInstanceOfSatisfying(DraftModelException.class,
                        ex -> assertThat(ex.reason()).isEqualTo("model_error"));
    }

    @Test
    void globalProfilesAreRefused() {
        assertThatThrownBy(() -> new AiProperties(true, null, "global.amazon.nova-2-lite-v1:0", null, null, null,
                null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
