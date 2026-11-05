package bank.alert.report;

import java.util.List;

/**
 * The validated client report for one event (schema alert-client-report-v2, ADR-0084). Everything in it comes from the
 * supplied public sources and cites them; nothing about a client, an account or a holding is in it. The fixed
 * text the application adds (the portfolio line, the disclaimer and the relationship manager's details) is
 * not part of the model's output and is not stored here.
 */
public record ClientReport(
        String headline,
        List<CitedText> executiveSummary,
        List<KeyFact> keyFacts,
        List<Implication> whyItMatters,
        String marketContext,
        List<String> whatToMonitor,
        String uncertainty,
        List<String> sourceRefs) {

    public static final String SCHEMA_VERSION = "alert-client-report-v2";

    public ClientReport {
        executiveSummary = List.copyOf(executiveSummary);
        keyFacts = List.copyOf(keyFacts);
        whyItMatters = List.copyOf(whyItMatters);
        whatToMonitor = List.copyOf(whatToMonitor);
        sourceRefs = List.copyOf(sourceRefs);
    }

    /** One sentence and the supplied sources that support it. */
    public record CitedText(String text, List<String> sourceRefs) {
        public CitedText {
            sourceRefs = List.copyOf(sourceRefs);
        }
    }

    /** A labelled fact, such as revenue, with its value and context, and the sources it comes from. */
    public record KeyFact(String label, String value, String context, List<String> sourceRefs) {
        public KeyFact {
            sourceRefs = List.copyOf(sourceRefs);
        }
    }

    /** Why the event matters: either a fact the sources state, or an implication explicitly marked as potential. */
    public record Implication(String text, Kind kind, List<String> sourceRefs) {
        public Implication {
            sourceRefs = List.copyOf(sourceRefs);
        }
    }

    public enum Kind { FACT, POTENTIAL_IMPLICATION }
}
