package bank.clienthealth.rules;

import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.ClientHealthFinding.EvidenceDetails;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import java.util.Objects;

/** Typed deterministic rule outcome consumed by RH-SF04 orchestration. */
public record DetectedSignal(
        String clientId,
        String accountId,
        SignalType type,
        String ruleVersion,
        Severity severity,
        SignalWindow window,
        EvidenceDetails evidence
) {
    public DetectedSignal {
        clientId = requireText(clientId, "clientId");
        accountId = requireText(accountId, "accountId");
        type = Objects.requireNonNull(type);
        ruleVersion = requireText(ruleVersion, "ruleVersion");
        severity = Objects.requireNonNull(severity);
        window = Objects.requireNonNull(window);
        evidence = Objects.requireNonNull(evidence);
        boolean correctEvidence = switch (type) {
            case RECURRING_CREDIT_INTERRUPTION ->
                    evidence instanceof ClientHealthFinding.RecurringCreditInterruptionEvidence;
            case TRANSACTION_ACTIVITY_DECLINE ->
                    evidence instanceof ClientHealthFinding.TransactionActivityDeclineEvidence;
            case SUSTAINED_NET_OUTFLOW ->
                    evidence instanceof ClientHealthFinding.SustainedNetOutflowEvidence;
        };
        if (!correctEvidence) {
            throw new IllegalArgumentException("Evidence does not match signal type");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
