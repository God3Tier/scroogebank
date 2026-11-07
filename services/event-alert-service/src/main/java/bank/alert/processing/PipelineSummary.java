package bank.alert.processing;

/** Counts for one pipeline pass: never titles, URLs, instruments or clients. */
public record PipelineSummary(
        int examined,
        int noMatch,
        int processed,
        int alertsCreated,
        int deferred,
        int errors,
        String skippedReason) {

    static PipelineSummary skipped(String reason) {
        return new PipelineSummary(0, 0, 0, 0, 0, 0, reason);
    }
}
