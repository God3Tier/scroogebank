package bank.clienthealth.rules.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.rules.RelationshipHealthRuleEngine;
import bank.clienthealth.rules.RelationshipHealthRuleset;
import org.junit.jupiter.api.Test;

class RuleEvaluationHarnessTest {
    @Test
    void passesAllLabelledScenariosWithExactExpectedOutcomes() {
        var report = new RuleEvaluationHarness(
                new RelationshipHealthRuleEngine(RelationshipHealthRuleset.v1()))
                .evaluate(RuleEvaluationScenarios.all());

        assertEquals(6, report.scenarioCount());
        assertTrue(report.deterministic());
        assertTrue(report.allScenariosPassed());
        assertEquals(6, report.overall().expected());
        assertEquals(6, report.overall().predicted());
        assertEquals(6, report.overall().truePositives());
        assertEquals(0, report.overall().falsePositives());
        assertEquals(0, report.overall().falseNegatives());
        assertEquals(6, report.overall().severityCorrect());
        assertEquals(1.0, report.overall().precision());
        assertEquals(1.0, report.overall().labelledCoverage());

        for (SignalType type : SignalType.values()) {
            var metrics = report.byRule().get(type);
            assertEquals(2, metrics.expected());
            assertEquals(2, metrics.truePositives());
            assertEquals(0, metrics.falsePositives());
            assertEquals(0, metrics.falseNegatives());
            assertEquals(2, metrics.severityCorrect());
        }
    }

    @Test
    void rendersReportWithSyntheticEvidenceWarningAndScenarioResults() {
        var report = new RuleEvaluationHarness(
                new RelationshipHealthRuleEngine(RelationshipHealthRuleset.v1()))
                .evaluate(RuleEvaluationScenarios.all());

        String markdown = RuleEvaluationMarkdown.render(report);

        assertTrue(markdown.contains("Synthetic labelled scenarios only"));
        assertTrue(markdown.contains("Overall: expected=6, predicted=6"));
        assertTrue(markdown.contains("precision=1.00"));
        assertTrue(markdown.contains("combined-positive: PASS"));
        assertTrue(markdown.contains("sparse-history-suppressed: PASS"));
    }
}
