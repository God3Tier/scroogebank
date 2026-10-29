package bank.clienthealth.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseMetrics;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;

class BedrockExplanationProviderTest {
    private static final String VALID = """
            {"schemaVersion":"1","summary":"Verified activity changed during the recent period.",
             "suggestedReviewStep":"Review verified activity before deciding whether contact is appropriate.",
             "evidenceCodes":["RECURRING_CREDIT_INTERRUPTION"],
             "disclaimer":"Human review required"}
            """;
    private final ExplanationRequest request = new ExplanationRequest("1",
            ExplanationRequest.PROMPT_VERSION, List.of(new ExplanationRequest.Fact(
                    SignalType.RECURRING_CREDIT_INTERRUPTION, "rule-v1", 180,
                    30, 67, 3, null, null, null, null, null)),
            new ExplanationRequest.Constraints(400, true));

    @Test
    void sendsBoundedStructuredConverseRequestAndAcceptsValidOutput() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(VALID));
        List<ExplanationMetricsPort.Event> events = new ArrayList<>();
        var provider = provider(client, events);

        assertThat(provider.explain(request).source()).isEqualTo(ExplanationResult.Source.BEDROCK);
        ArgumentCaptor<ConverseRequest> sent = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(client).converse(sent.capture());
        assertThat(sent.getValue().modelId()).isEqualTo("model-test");
        assertThat(sent.getValue().inferenceConfig().maxTokens()).isEqualTo(256);
        assertThat(sent.getValue().inferenceConfig().temperature()).isZero();
        assertThat(sent.getValue().outputConfig().textFormat().typeAsString()).isEqualTo("json_schema");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(ExplanationMetricsPort.Type.MODEL_CALL);
            assertThat(event.inputTokens()).isEqualTo(42);
            assertThat(event.estimatedCostUsd()).isNull();
        });
    }

    @Test
    void retriesOneTransientFailureThenFallsBackWithSafeMetrics() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        when(client.converse(any(ConverseRequest.class))).thenThrow(ThrottlingException.builder().build());
        List<ExplanationMetricsPort.Event> events = new ArrayList<>();
        BedrockExplanationProvider bedrock = provider(client, events);
        var safe = new SafeExplanationProvider(properties(true), bedrock,
                new TemplateExplanationProvider(), events::add);

        assertThat(safe.explain(request).source()).isEqualTo(ExplanationResult.Source.TEMPLATE_FALLBACK);
        verify(client, times(2)).converse(any(ConverseRequest.class));
        assertThat(events).filteredOn(event -> event.type() == ExplanationMetricsPort.Type.PROVIDER_FAILURE)
                .hasSize(2).extracting(ExplanationMetricsPort.Event::attempt).containsExactly(1, 2);
        assertThat(events).extracting(ExplanationMetricsPort.Event::type)
                .contains(ExplanationMetricsPort.Type.FALLBACK);
    }

    @Test
    void recordsFailedAndSuccessfulRetryAttemptsSeparately() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        when(client.converse(any(ConverseRequest.class)))
                .thenThrow(ThrottlingException.builder().build())
                .thenReturn(response(VALID));
        List<ExplanationMetricsPort.Event> events = new ArrayList<>();
        var provider = provider(client, events);

        assertThat(provider.explain(request).source()).isEqualTo(ExplanationResult.Source.BEDROCK);
        assertThat(events).extracting(
                ExplanationMetricsPort.Event::type,
                ExplanationMetricsPort.Event::attempt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                ExplanationMetricsPort.Type.PROVIDER_FAILURE, 1),
                        org.assertj.core.groups.Tuple.tuple(
                                ExplanationMetricsPort.Type.MODEL_CALL, 2));
    }

    @Test
    void rejectsOversizedInputWithoutRecordingABedrockCall() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        List<ExplanationMetricsPort.Event> events = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        var properties = new ClientHealthAiProperties(
                true, "model-test", Duration.ofSeconds(5), 2, 10, 256, null, null);
        var bedrock = new BedrockExplanationProvider(client, properties,
                new ExplanationSafetyValidator(mapper), events::add, mapper);
        var safe = new SafeExplanationProvider(properties, bedrock,
                new TemplateExplanationProvider(), events::add);

        assertThat(safe.explain(request).source()).isEqualTo(ExplanationResult.Source.TEMPLATE_FALLBACK);
        verify(client, never()).converse(any(ConverseRequest.class));
        assertThat(events).noneMatch(event -> event.type() == ExplanationMetricsPort.Type.MODEL_CALL
                || event.type() == ExplanationMetricsPort.Type.PROVIDER_FAILURE);
        assertThat(events).extracting(ExplanationMetricsPort.Event::type)
                .containsExactly(ExplanationMetricsPort.Type.VALIDATION_REJECTION,
                        ExplanationMetricsPort.Type.FALLBACK);
    }

    @Test
    void killSwitchMakesZeroBedrockCalls() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        List<ExplanationMetricsPort.Event> events = new ArrayList<>();
        var safe = new SafeExplanationProvider(properties(false), provider(client, events),
                new TemplateExplanationProvider(), events::add);

        assertThat(safe.explain(request).source()).isEqualTo(ExplanationResult.Source.TEMPLATE_FALLBACK);
        verify(client, never()).converse(any(ConverseRequest.class));
        assertThat(events).extracting(ExplanationMetricsPort.Event::reason).contains("AI_DISABLED");
    }

    @Test
    void malformedModelOutputFallsBackWithoutRetry() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        when(client.converse(any(ConverseRequest.class))).thenReturn(response("not-json"));
        List<ExplanationMetricsPort.Event> events = new ArrayList<>();
        var safe = new SafeExplanationProvider(properties(true), provider(client, events),
                new TemplateExplanationProvider(), events::add);

        assertThat(safe.explain(request).source()).isEqualTo(ExplanationResult.Source.TEMPLATE_FALLBACK);
        verify(client).converse(any(ConverseRequest.class));
        assertThat(events).extracting(ExplanationMetricsPort.Event::type)
                .contains(ExplanationMetricsPort.Type.VALIDATION_REJECTION,
                        ExplanationMetricsPort.Type.FALLBACK);
    }

    private static BedrockExplanationProvider provider(BedrockRuntimeClient client,
            List<ExplanationMetricsPort.Event> events) {
        ObjectMapper mapper = new ObjectMapper();
        return new BedrockExplanationProvider(client, properties(true),
                new ExplanationSafetyValidator(mapper), events::add, mapper);
    }

    private static ClientHealthAiProperties properties(boolean enabled) {
        return new ClientHealthAiProperties(enabled, enabled ? "model-test" : null,
                Duration.ofSeconds(5), 2, 6000, 256, null, null);
    }

    private static ConverseResponse response(String text) {
        return ConverseResponse.builder()
                .output(output -> output.message(Message.builder()
                        .content(ContentBlock.fromText(text)).build()))
                .usage(TokenUsage.builder().inputTokens(42).outputTokens(18).build())
                .metrics(ConverseMetrics.builder().latencyMs(25L).build())
                .stopReason("end_turn").build();
    }
}
