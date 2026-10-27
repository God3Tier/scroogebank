package bank.clienthealth.rules;

import bank.clienthealth.activity.application.ActivityBaselineService.BaselineResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Evaluates one immutable ruleset without task, suppression, or AI behavior. */
public final class RelationshipHealthRuleEngine {
    private final RelationshipHealthRuleset ruleset;
    private final List<RelationshipHealthRule> rules;

    public RelationshipHealthRuleEngine(RelationshipHealthRuleset ruleset) {
        this.ruleset = Objects.requireNonNull(ruleset);
        this.rules = List.of(
                new RecurringCreditInterruptionRule(ruleset.recurringCredit()),
                new TransactionActivityDeclineRule(ruleset.activityDecline()),
                new SustainedNetOutflowRule(ruleset.netOutflow()));
    }

    public String rulesetVersion() {
        return ruleset.version();
    }

    public RuleEvaluation evaluate(BaselineResult baseline) {
        Objects.requireNonNull(baseline);
        List<DetectedSignal> outcomes = new ArrayList<>();
        baseline.clients().values().stream()
                .sorted(Comparator.comparing(client -> client.clientId()))
                .forEach(client -> rules.forEach(rule ->
                        outcomes.addAll(rule.evaluate(baseline, client))));
        return new RuleEvaluation(ruleset.version(), outcomes);
    }

    public record RuleEvaluation(String rulesetVersion, List<DetectedSignal> outcomes) {
        public RuleEvaluation {
            rulesetVersion = Objects.requireNonNull(rulesetVersion);
            outcomes = List.copyOf(outcomes);
        }
    }
}
