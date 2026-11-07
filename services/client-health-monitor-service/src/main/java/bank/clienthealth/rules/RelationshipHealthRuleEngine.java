package bank.clienthealth.rules;

import bank.clienthealth.activity.application.ActivityBaselineService.BaselineResult;
import bank.clienthealth.metrics.ClientHealthMetrics;
import bank.clienthealth.metrics.ClientHealthMetrics.Rule;
import bank.clienthealth.metrics.ClientHealthMetrics.RuleOutcome;
import bank.clienthealth.metrics.ClientHealthMetrics.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Evaluates one immutable ruleset without task, suppression, or AI behavior. */
public final class RelationshipHealthRuleEngine {
    private final RelationshipHealthRuleset ruleset;
    private final List<InstrumentedRule> rules;
    private final ClientHealthMetrics metrics;

    public RelationshipHealthRuleEngine(RelationshipHealthRuleset ruleset) {
        this(ruleset, ClientHealthMetrics.noOp());
    }

    public RelationshipHealthRuleEngine(RelationshipHealthRuleset ruleset, ClientHealthMetrics metrics) {
        this.ruleset = Objects.requireNonNull(ruleset);
        this.metrics = Objects.requireNonNull(metrics);
        this.rules = List.of(
                new InstrumentedRule(Rule.RECURRING_CREDIT,
                        new RecurringCreditInterruptionRule(ruleset.recurringCredit())),
                new InstrumentedRule(Rule.ACTIVITY_DECLINE,
                        new TransactionActivityDeclineRule(ruleset.activityDecline())),
                new InstrumentedRule(Rule.NET_OUTFLOW,
                        new SustainedNetOutflowRule(ruleset.netOutflow())));
    }

    public String rulesetVersion() {
        return ruleset.version();
    }

    public RuleEvaluation evaluate(BaselineResult baseline) {
        Objects.requireNonNull(baseline);
        List<DetectedSignal> outcomes = new ArrayList<>();
        baseline.clients().values().stream()
                .sorted(Comparator.comparing(client -> client.clientId()))
                .forEach(client -> rules.forEach(rule -> {
                    var evaluation = rule.delegate().evaluate(baseline, client);
                    outcomes.addAll(evaluation.signals());
                    RuleOutcome outcome = !evaluation.signals().isEmpty()
                            ? RuleOutcome.TRIGGERED
                            : evaluation.sufficientInput() ? RuleOutcome.NOT_TRIGGERED : RuleOutcome.INSUFFICIENT;
                    Severity severity = evaluation.signals().stream()
                            .map(DetectedSignal::severity)
                            .map(RelationshipHealthRuleEngine::severity)
                            .max(Enum::compareTo).orElse(Severity.NONE);
                    metrics.ruleEvaluation(rule.type(), outcome, severity);
                    evaluation.signals().forEach(signal ->
                            metrics.ruleSignal(rule.type(), severity(signal.severity())));
                }));
        return new RuleEvaluation(ruleset.version(), outcomes);
    }

    private static Severity severity(bank.clienthealth.contract.ClientHealthFinding.Severity severity) {
        return switch (severity) {
            case LOW -> Severity.LOW;
            case MEDIUM -> Severity.MEDIUM;
            case HIGH -> Severity.HIGH;
        };
    }

    private record InstrumentedRule(Rule type, RelationshipHealthRule delegate) { }

    public record RuleEvaluation(String rulesetVersion, List<DetectedSignal> outcomes) {
        public RuleEvaluation {
            rulesetVersion = Objects.requireNonNull(rulesetVersion);
            outcomes = List.copyOf(outcomes);
        }
    }
}
