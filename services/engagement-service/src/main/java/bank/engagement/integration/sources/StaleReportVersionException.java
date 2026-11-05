package bank.engagement.integration.sources;

/** The report was changed since the agent looked, or there is none (HTTP 409 from event-alert-service). */
public class StaleReportVersionException extends RuntimeException {

    public StaleReportVersionException() {
        super("report is not at the expected version");
    }
}
