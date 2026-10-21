package bank.clienthealth.rules;

import bank.clienthealth.activity.application.ActivityBaselineService.BaselineResult;
import bank.clienthealth.activity.application.ActivityBaselineService.ClientBaseline;
import bank.clienthealth.activity.application.ActivityBaselineService.PeriodSummary;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import bank.clienthealth.contract.ClientHealthFinding.SustainedNetOutflowEvidence;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

final class SustainedNetOutflowRule implements RelationshipHealthRule {
    private final RelationshipHealthRuleset.NetOutflow config;

    SustainedNetOutflowRule(RelationshipHealthRuleset.NetOutflow config) {
        this.config = config;
    }

    @Override
    public List<DetectedSignal> evaluate(BaselineResult baseline, ClientBaseline client) {
        List<DetectedSignal> signals = new ArrayList<>();
        client.accounts().forEach(account -> {
            if (account.periods().size() < 2) {
                return;
            }
            List<PeriodSummary> recent = account.periods()
                    .subList(account.periods().size() - 2, account.periods().size());
            BigDecimal maximumNet = config.minimumPeriodOutflow().negate();
            if (recent.stream().anyMatch(period ->
                    period.summary().netFlow().compareTo(maximumNet) > 0)) {
                return;
            }
            BigDecimal deposits = recent.stream()
                    .map(period -> period.summary().deposits())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal withdrawals = recent.stream()
                    .map(period -> period.summary().withdrawals())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal netFlow = deposits.subtract(withdrawals);
            BigDecimal ratio = deposits.signum() == 0
                    ? null
                    : withdrawals.divide(deposits, 4, RoundingMode.HALF_UP);
            if (ratio != null && ratio.compareTo(config.lowRatio()) < 0) {
                return;
            }

            BigDecimal baselineAverage = account.baseline().netFlow()
                    .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            BigDecimal recentAverage = netFlow.divide(
                    BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            BigDecimal denominator = account.baseline().deposits()
                    .add(account.baseline().withdrawals())
                    .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            if (denominator.signum() == 0) {
                return;
            }
            BigDecimal changePercent = recentAverage.subtract(baselineAverage)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(denominator, 2, RoundingMode.HALF_UP);
            if (changePercent.signum() >= 0) {
                return;
            }

            Severity severity = ratio == null || ratio.compareTo(config.highRatio()) >= 0
                    ? Severity.HIGH
                    : ratio.compareTo(config.mediumRatio()) >= 0
                            ? Severity.MEDIUM : Severity.LOW;
            signals.add(new DetectedSignal(
                    client.clientId(),
                    account.accountId(),
                    SignalType.SUSTAINED_NET_OUTFLOW,
                    config.ruleVersion(),
                    severity,
                    new SignalWindow(
                            baseline.baselineStart(), recent.getFirst().fromInclusive(),
                            recent.getFirst().fromInclusive(), baseline.analysisCutoff()),
                    new SustainedNetOutflowEvidence(
                            2, deposits, withdrawals, netFlow,
                            account.comparison().currency(), changePercent)));
        });
        return List.copyOf(signals);
    }
}
