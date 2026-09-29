package bank.alert.ai;

import bank.alert.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BedrockAiModelGatewayTest {
    private static final String MODEL = "global.openai.gpt-5.6-luna";

    @Test
    void sendsOnlyTheBoundedPublicRequestAndReturnsText() {
        BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
        Message message = Message.builder().content(ContentBlock.fromText("{\"abstain\":true}"))
                .build();
        when(client.converse(any(ConverseRequest.class))).thenReturn(ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build()).build());
        BedrockAiModelGateway gateway = new BedrockAiModelGateway(client, properties(true));

        String result = gateway.invoke(request(MODEL), Duration.ofSeconds(12));

        assertThat(result).isEqualTo("{\"abstain\":true}");
        ArgumentCaptor<ConverseRequest> captor = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(client).converse(captor.capture());
        ConverseRequest sent = captor.getValue();
        assertThat(sent.modelId()).isEqualTo(MODEL);
        assertThat(sent.messages()).singleElement().satisfies(sentMessage ->
                assertThat(sentMessage.content()).singleElement().satisfies(content ->
                        assertThat(content.text()).isEqualTo("{\"referenceId\":\"public-1\"}")));
        assertThat(sent.system()).singleElement().satisfies(system ->
                assertThat(system.text()).contains("public only", "CLASSIFY_PUBLIC_EVENT",
                        "alert-public-v1", "structured JSON"));
        assertThat(sent.inferenceConfig().maxTokens()).isEqualTo(300);
        assertThat(sent.inferenceConfig().temperature()).isZero();
        assertThat(sent.outputConfig().textFormat().typeAsString()).isEqualTo("json_schema");
        assertThat(sent.outputConfig().textFormat().structure().jsonSchema().name())
                .isEqualTo("classify-public-event");
        assertThat(sent.outputConfig().textFormat().structure().jsonSchema().schema())
                .isEqualTo("classification-v1");
        assertThat(sent.overrideConfiguration().flatMap(configuration -> configuration.apiCallTimeout()))
                .contains(Duration.ofSeconds(12));
    }

    @Test
    void disabledGatewayFailsClosedWithoutCallingAws() {
        BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
        BedrockAiModelGateway gateway = new BedrockAiModelGateway(client, properties(false));

        assertThatThrownBy(() -> gateway.invoke(request(MODEL), Duration.ofSeconds(12)))
                .isInstanceOfSatisfying(AiProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("MODEL_DISABLED");
                    assertThat(exception.retryable()).isFalse();
                });
        verify(client, never()).converse(any(ConverseRequest.class));
    }

    @Test
    void rejectsAnUnapprovedModelWithoutCallingAws() {
        BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
        BedrockAiModelGateway gateway = new BedrockAiModelGateway(client, properties(true));

        assertThatThrownBy(() -> gateway.invoke(request("other-model"), Duration.ofSeconds(12)))
                .isInstanceOfSatisfying(AiProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("MODEL_NOT_ALLOWED");
                    assertThat(exception.retryable()).isFalse();
                });
        verify(client, never()).converse(any(ConverseRequest.class));
    }

    @Test
    void mapsThrottlingToASafeRetryableFailure() {
        BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
        when(client.converse(any(ConverseRequest.class))).thenThrow(
                ThrottlingException.builder().message("synthetic throttle").build());
        BedrockAiModelGateway gateway = new BedrockAiModelGateway(client, properties(true));

        assertThatThrownBy(() -> gateway.invoke(request(MODEL), Duration.ofSeconds(12)))
                .isInstanceOfSatisfying(AiProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("MODEL_THROTTLED");
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.getMessage()).doesNotContain("synthetic throttle");
                });
    }

    private static AiModelRequest request(String model) {
        return new AiModelRequest("CLASSIFY_PUBLIC_EVENT", model, "alert-public-v1",
                "Use public only.", "{\"referenceId\":\"public-1\"}",
                "classification-v1", 300);
    }

    private static AiProperties properties(boolean enabled) {
        return new AiProperties(enabled, MODEL, "alert-public-v1", 12_000, 8_000,
                1_200, 2, Duration.ofSeconds(20), new BigDecimal("0.60"));
    }
}
