package bank.alert.report;

/** Writes the one client report for an event from its public sources (ADR-0084). */
public interface ClientReportGenerator {

    ReportOutcome generate(ReportInput input);
}
