package bank.clienthealth.rules.evaluation;

import bank.clienthealth.rules.evaluation.RuleEvaluationHarness.EvaluationReport;
import bank.clienthealth.rules.evaluation.RuleEvaluationHarness.Metrics;
import java.util.Locale;

final class RuleEvaluationMarkdown {
    private RuleEvaluationMarkdown() { }

    static String render(EvaluationReport report) {
        StringBuilder output = new StringBuilder("""
                # Client Health offline rule evaluation

                > Synthetic labelled scenarios only. These figures are not real-client
                > accuracy, financial-risk, or production-performance claims.

                """);
        output.append("- Scenarios: ").append(report.scenarioCount()).append('\n');
        output.append("- Deterministic repeat: ").append(report.deterministic()).append('\n');
        output.append("- All labelled scenarios passed: ")
                .append(report.allScenariosPassed()).append('\n');
        appendMetrics(output, "- Overall", report.overall());
        output.append("\n## Per rule\n\n");
        report.byRule().forEach((type, metrics) ->
                appendMetrics(output, "- " + type, metrics));
        output.append("\n## Scenarios\n\n");
        report.scenarios().forEach(scenario ->
                output.append("- ").append(scenario.name()).append(": ")
                        .append(scenario.passed() ? "PASS" : "FAIL").append('\n'));
        return output.toString();
    }

    private static void appendMetrics(StringBuilder output, String label, Metrics metrics) {
        output.append(label)
                .append(": expected=").append(metrics.expected())
                .append(", predicted=").append(metrics.predicted())
                .append(", TP=").append(metrics.truePositives())
                .append(", FP=").append(metrics.falsePositives())
                .append(", FN=").append(metrics.falseNegatives())
                .append(", severity-correct=").append(metrics.severityCorrect())
                .append(", precision=").append(format(metrics.precision()))
                .append(", labelled-coverage=").append(format(metrics.labelledCoverage()))
                .append('\n');
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
