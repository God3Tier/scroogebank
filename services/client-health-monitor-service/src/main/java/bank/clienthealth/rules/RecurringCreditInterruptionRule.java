package bank.clienthealth.rules;

import bank.clienthealth.activity.application.ActivityBaselineService.BaselineResult;
import bank.clienthealth.activity.application.ActivityBaselineService.ClientBaseline;
import bank.clienthealth.activity.application.ActivityBaselineService.RecurringCredit;
import bank.clienthealth.contract.ClientHealthFinding.RecurringCreditInterruptionEvidence;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RecurringCreditInterruptionRule implements RelationshipHealthRule {
    private final RelationshipHealthRuleset.RecurringCredit config;

    RecurringCreditInterruptionRule(RelationshipHealthRuleset.RecurringCredit config) {
        this.config = config;
    }

    @Override
    public List<DetectedSignal> evaluate(BaselineResult baseline, ClientBaseline client) {
        List<DetectedSignal> signals = new ArrayList<>();
        client.accounts().forEach(account -> account.recurringCredits().forEach(series -> {
            if (series.occurrences().size() < config.minimumOccurrences()) {
                return;
            }
            List<RecurringCredit> occurrences = series.occurrences().stream()
                    .sorted(Comparator.comparing(RecurringCredit::transactionDate))
                    .toList();
            int cadenceDays = medianCadenceDays(occurrences);
            if (cadenceDays <= 0 || unstableAmounts(occurrences)) {
                return;
            }
            int graceDays = Math.max(
                    config.minimumGraceDays(),
                    BigDecimal.valueOf(cadenceDays)
                            .multiply(config.cadenceGraceFraction())
                            .setScale(0, RoundingMode.CEILING)
                            .intValueExact());
            int daysSinceLast = Math.toIntExact(Duration.between(
                    occurrences.getLast().transactionDate(), baseline.analysisCutoff()).toDays());
            if (daysSinceLast <= cadenceDays + graceDays) {
                return;
            }
            int missedCycles = Math.max(1, (daysSinceLast - graceDays) / cadenceDays);
            Severity severity = missedCycles >= 3
                    ? Severity.HIGH : missedCycles == 2 ? Severity.MEDIUM : Severity.LOW;
            signals.add(new DetectedSignal(
                    client.clientId(),
                    account.accountId(),
                    SignalType.RECURRING_CREDIT_INTERRUPTION,
                    config.ruleVersion(),
                    severity,
                    new SignalWindow(
                            baseline.historyStart(), baseline.comparisonStart(),
                            baseline.comparisonStart(), baseline.analysisCutoff()),
                    new RecurringCreditInterruptionEvidence(
                            cadenceDays, occurrences.size(), daysSinceLast)));
        }));
        return List.copyOf(signals);
    }

    private int medianCadenceDays(List<RecurringCredit> occurrences) {
        List<Long> intervals = new ArrayList<>();
        for (int index = 1; index < occurrences.size(); index++) {
            intervals.add(Duration.between(
                    occurrences.get(index - 1).transactionDate(),
                    occurrences.get(index).transactionDate()).toDays());
        }
        intervals.sort(Long::compareTo);
        int middle = intervals.size() / 2;
        long median = intervals.size() % 2 == 1
                ? intervals.get(middle)
                : (intervals.get(middle - 1) + intervals.get(middle)) / 2;
        return Math.toIntExact(median);
    }

    private boolean unstableAmounts(List<RecurringCredit> occurrences) {
        List<BigDecimal> amounts = occurrences.stream()
                .map(RecurringCredit::amount)
                .sorted()
                .toList();
        int middle = amounts.size() / 2;
        BigDecimal median = amounts.size() % 2 == 1
                ? amounts.get(middle)
                : amounts.get(middle - 1).add(amounts.get(middle))
                        .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        if (median.signum() <= 0) {
            return true;
        }
        return amounts.stream().anyMatch(amount -> amount.subtract(median).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(median, 6, RoundingMode.HALF_UP)
                .compareTo(config.maximumAmountVariationPercent()) > 0);
    }
}
