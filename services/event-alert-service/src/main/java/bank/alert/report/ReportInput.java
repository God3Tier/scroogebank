package bank.alert.report;

import bank.alert.ai.PublicEvidence;

import java.util.List;

/**
 * Everything the model is told about one ticker's new news: the public name of the company, at most five public
 * sources, and what an earlier report on the same company said in the last 72 hours, so the new report does not
 * repeat it (ADR-0084). No client, account or holding data is here.
 */
public record ReportInput(List<InstrumentRef> instruments, List<PublicEvidence> evidence,
        List<PriorReport> alreadyReported) {

    /** An instrument's public identity. */
    public record InstrumentRef(String instrumentId, String ticker, String issuerName) {}

    /** The public text of an earlier report on the same company. */
    public record PriorReport(String headline, List<String> summary) {
        public PriorReport {
            summary = List.copyOf(summary);
        }
    }

    public ReportInput {
        instruments = List.copyOf(instruments);
        evidence = List.copyOf(evidence);
        alreadyReported = alreadyReported == null ? List.of() : List.copyOf(alreadyReported);
        if (instruments.isEmpty()) throw new IllegalArgumentException("a report needs at least one instrument");
        if (evidence.isEmpty()) throw new IllegalArgumentException("a report needs at least one source");
    }

    /** A report with nothing earlier to avoid repeating. */
    public ReportInput(List<InstrumentRef> instruments, List<PublicEvidence> evidence) {
        this(instruments, evidence, List.of());
    }
}
