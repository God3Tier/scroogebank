package bank.clienthealth.explanation;

import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.ClientHealthFinding.SignalEvidence;
import bank.clienthealth.task.domain.RelationshipTask;
import java.time.Duration;
import java.util.List;

/** Converts persisted evidence to the identifier-free Contract-E boundary. */
public final class ExplanationRequestFactory {
    private ExplanationRequestFactory() { }

    public static ExplanationRequest from(RelationshipTask task) {
        return request(task.evidence().stream().map(RelationshipTask.Evidence::signal).toList());
    }

    public static ExplanationRequest from(ClientHealthFinding finding) {
        return request(finding.signals());
    }

    private static ExplanationRequest request(
            List<? extends SignalEvidence<? extends ClientHealthFinding.EvidenceDetails>> signals
    ) {
        List<ExplanationRequest.Fact> facts = signals.stream()
                .map(ExplanationRequestFactory::fact)
                .toList();
        return new ExplanationRequest("1", ExplanationRequest.PROMPT_VERSION, facts,
                new ExplanationRequest.Constraints(400, true));
    }

    private static ExplanationRequest.Fact fact(
            SignalEvidence<? extends ClientHealthFinding.EvidenceDetails> signal
    ) {
        int days = Math.toIntExact(Duration.between(
                signal.window().baselineStart(), signal.window().comparisonEnd()).toDays());
        Integer cadence = null, since = null, occurrences = null, average = null, comparison = null, periods = null;
        java.math.BigDecimal change = null, baselineChange = null;
        if (signal.evidence() instanceof ClientHealthFinding.RecurringCreditInterruptionEvidence value) {
            cadence = value.expectedCadenceDays(); since = value.daysSinceLastOccurrence();
            occurrences = value.previousOccurrenceCount();
        } else if (signal.evidence() instanceof ClientHealthFinding.TransactionActivityDeclineEvidence value) {
            average = value.baselineMonthlyAverageCount(); comparison = value.comparisonCount();
            change = value.changePercent();
        } else if (signal.evidence() instanceof ClientHealthFinding.SustainedNetOutflowEvidence value) {
            periods = value.periodCount(); baselineChange = value.changeFromBaselinePercent();
        }
        return new ExplanationRequest.Fact(signal.type(), signal.ruleVersion(), days,
                cadence, since, occurrences, average, comparison, change, periods, baselineChange);
    }
}
