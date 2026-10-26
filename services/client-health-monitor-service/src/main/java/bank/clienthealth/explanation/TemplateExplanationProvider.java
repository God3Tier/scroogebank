package bank.clienthealth.explanation;

import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import java.util.Comparator;

public final class TemplateExplanationProvider implements ExplanationProvider {
    private static final String STEP =
            "Review the verified account activity before deciding whether client contact is appropriate.";

    @Override
    public ExplanationResult explain(ExplanationRequest request) {
        var codes = request.facts().stream().map(ExplanationRequest.Fact::evidenceCode)
                .distinct().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
        String summary = codes.stream().map(TemplateExplanationProvider::clause)
                .collect(java.util.stream.Collectors.joining(" "));
        return new ExplanationResult("1", summary, STEP, codes, ExplanationResult.DISCLAIMER,
                ExplanationResult.Source.TEMPLATE_FALLBACK, null);
    }

    private static String clause(SignalType type) {
        return switch (type) {
            case RECURRING_CREDIT_INTERRUPTION ->
                    "An established recurring credit was not observed within its expected period.";
            case TRANSACTION_ACTIVITY_DECLINE ->
                    "Client-initiated transaction activity declined relative to prior activity.";
            case SUSTAINED_NET_OUTFLOW ->
                    "Withdrawals exceeded deposits across multiple recent periods.";
        };
    }
}
