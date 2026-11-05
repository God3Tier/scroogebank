package bank.engagement.integration.sources;

/** The report was already sent to this client, or another request is sending it now. */
public class ReportAlreadySentException extends RuntimeException {

    public ReportAlreadySentException() {
        super("report already sent or being sent");
    }
}
