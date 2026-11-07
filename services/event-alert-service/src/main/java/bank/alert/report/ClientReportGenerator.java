package bank.alert.report;

/** Writes the one client report for an event from its public sources (ADR-0084). */
public interface ClientReportGenerator {

    ReportOutcome generate(ReportInput input);

    /**
     * The same, told how many drafts of this event's report have already been refused. After
     * {@link SafeClientReportGenerator#RELAX_SUMMARY_AFTER_FAILED_DRAFTS} refusals a one-sentence summary is accepted.
     */
    default ReportOutcome generate(ReportInput input, int failedDraftsSoFar) {
        return generate(input);
    }
}
