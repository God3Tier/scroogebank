package bank.alert.report;

/**
 * The fixed text the application adds to a client report (ADR-0084). The model never writes it and is never told
 * about a client, so these lines cannot be altered or invented by a model and say nothing about a holding.
 */
public final class ReportText {

    /** The informational-purpose disclaimer, the same on every report. */
    public static final String DISCLAIMER = "This report is for information only. It is based on public sources, is not "
            + "investment advice, and does not take your personal circumstances into account.";

    private ReportText() {
    }

    /** The line that ties a report to a client's portfolio without saying how much or what is held. */
    public static String portfolioLine(String issuer) {
        return "Your portfolio has exposure to " + issuer + ", which may be affected by this event.";
    }
}
