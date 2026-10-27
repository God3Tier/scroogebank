package bank.clienthealth.explanation;

import bank.clienthealth.explanation.ExplanationMetricsPort.Event;
import bank.clienthealth.explanation.ExplanationMetricsPort.Type;

/** Kill switch and fail-safe boundary: every provider failure becomes deterministic wording. */
public final class SafeExplanationProvider implements ExplanationProvider {
    private final ClientHealthAiProperties properties;
    private final BedrockExplanationProvider bedrock;
    private final TemplateExplanationProvider template;
    private final ExplanationMetricsPort metrics;

    public SafeExplanationProvider(ClientHealthAiProperties properties,
            BedrockExplanationProvider bedrock, TemplateExplanationProvider template,
            ExplanationMetricsPort metrics) {
        this.properties = properties;
        this.bedrock = bedrock;
        this.template = template;
        this.metrics = metrics;
    }

    @Override
    public ExplanationResult explain(ExplanationRequest request) {
        if (!properties.enabled()) return fallback(request, "AI_DISABLED");
        try {
            return bedrock.explain(request);
        } catch (ExplanationSafetyValidator.UnsafeExplanationException unsafe) {
            metrics.record(new Event(Type.VALIDATION_REJECTION, unsafe.getMessage(),
                    properties.modelId(), 0, 0, 0, 0, null));
            return fallback(request, unsafe.getMessage());
        } catch (BedrockExplanationProvider.ProviderException failed) {
            if (failed.attempts() == 0) {
                metrics.record(new Event(Type.VALIDATION_REJECTION, failed.code(),
                        properties.modelId(), 0, 0, 0, 0, null));
            }
            return fallback(request, failed.code());
        }
    }

    private ExplanationResult fallback(ExplanationRequest request, String reason) {
        metrics.record(new Event(Type.FALLBACK, reason, properties.modelId(), 0, 0, 0, 0, null));
        return template.explain(request);
    }
}
