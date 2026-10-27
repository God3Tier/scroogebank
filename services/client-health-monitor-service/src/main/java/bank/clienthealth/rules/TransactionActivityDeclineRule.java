package bank.clienthealth.rules;

import bank.clienthealth.activity.application.ActivityBaselineService.BaselineResult;
import bank.clienthealth.activity.application.ActivityBaselineService.ClientBaseline;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import bank.clienthealth.contract.ClientHealthFinding.TransactionActivityDeclineEvidence;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

final class TransactionActivityDeclineRule implements RelationshipHealthRule {
    private final RelationshipHealthRuleset.ActivityDecline config;

    TransactionActivityDeclineRule(RelationshipHealthRuleset.ActivityDecline config) {
        this.config = config;
    }

    @Override
    public List<DetectedSignal> evaluate(BaselineResult baseline, ClientBaseline client) {
        List<DetectedSignal> signals = new ArrayList<>();
        client.accounts().forEach(account -> {
            long baselineCount = account.baseline().customerInitiatedCount();
            if (baselineCount < config.minimumBaselineCount()
                    || account.baseline().unknownCount() > 0
                    || account.comparison().unknownCount() > 0) {
                return;
            }
            BigDecimal monthlyAverage = BigDecimal.valueOf(baselineCount)
                    .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            BigDecimal comparisonCount = BigDecimal.valueOf(
                    account.comparison().customerInitiatedCount());
            BigDecimal declinePercent = monthlyAverage.subtract(comparisonCount)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(monthlyAverage, 2, RoundingMode.HALF_UP);
            if (declinePercent.compareTo(config.lowPercent()) < 0) {
                return;
            }
            Severity severity = declinePercent.compareTo(config.highPercent()) >= 0
                    ? Severity.HIGH
                    : declinePercent.compareTo(config.mediumPercent()) >= 0
                            ? Severity.MEDIUM : Severity.LOW;
            signals.add(new DetectedSignal(
                    client.clientId(),
                    account.accountId(),
                    SignalType.TRANSACTION_ACTIVITY_DECLINE,
                    config.ruleVersion(),
                    severity,
                    new SignalWindow(
                            baseline.baselineStart(), baseline.comparisonStart(),
                            baseline.comparisonStart(), baseline.analysisCutoff()),
                    new TransactionActivityDeclineEvidence(
                            monthlyAverage.setScale(0, RoundingMode.HALF_UP).intValueExact(),
                            Math.toIntExact(account.comparison().customerInitiatedCount()),
                            declinePercent.negate())));
        });
        return List.copyOf(signals);
    }
}
