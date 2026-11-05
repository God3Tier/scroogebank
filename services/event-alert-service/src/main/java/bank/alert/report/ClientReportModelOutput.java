package bank.alert.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** What the model returns for alert-client-report-v2, before it is validated. Unknown fields are refused. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ClientReportModelOutput(
        Boolean abstain,
        String abstentionReason,
        String headline,
        List<ClientReport.CitedText> executiveSummary,
        List<ClientReport.KeyFact> keyFacts,
        List<ClientReport.Implication> whyItMatters,
        String marketContext,
        List<String> whatToMonitor,
        String uncertainty,
        List<String> sourceRefs) {
}
