package bank.alert.report;

import java.time.Instant;

/** The result of asking the model for a report. PENDING means "try again later"; ABSTAINED means "not this time". */
public record ReportOutcome(Status status, ClientReport report, String reasonCode, int attempts,
        String modelVersion, String promptVersion, Instant generatedAt) {

    public enum Status { GENERATED, ABSTAINED, PENDING }

    public static ReportOutcome of(Status status, ClientReport report, String reasonCode, int attempts) {
        return new ReportOutcome(status, report, reasonCode, attempts, null, null, null);
    }
}
