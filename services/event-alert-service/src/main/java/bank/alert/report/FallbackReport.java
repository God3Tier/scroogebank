package bank.alert.report;

import bank.alert.ai.PublicEvidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A short, plain report assembled from the public sources themselves, with no model, for an event whose report the model could not
 * write after every try. It is the safe way to fail: the agent always has something to read, edit and approve, never an error.
 *
 * <p>It says only what the sources say: the newest title as the headline and first sentence, and the first full sentence of a
 * source's text as the second when there is one. It writes nothing of its own. Key facts, why it matters, what to monitor and the
 * uncertainty note are left empty, and the page and the PDF leave out a section that has nothing in it. It cites the sources, says
 * nothing about a client, and carries no advice wording; if the sources do, there is no fallback.
 */
final class FallbackReport {

    static final String MODEL = "basic-summary-v1";
    static final String PROMPT_VERSION = "fallback-v1";

    private static final int MAX_SOURCES = 3;
    private static final int MAX_SENTENCE = 580;
    private static final Pattern PUBLISHER_SUFFIX = Pattern.compile("\\s+[-|\u2013\u2014]\\s+[^-|\u2013\u2014]{2,40}$");
    private static final Pattern FIRST_SENTENCE = Pattern.compile("^(.{20,}?[.!?])(\\s|$)", Pattern.DOTALL);

    private FallbackReport() {
    }

    static Optional<ClientReport> build(List<PublicEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return Optional.empty();
        List<PublicEvidence> newestFirst = evidence.stream()
                .sorted(Comparator.comparing(PublicEvidence::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_SOURCES).toList();
        PublicEvidence lead = newestFirst.getFirst();

        String title = cleanTitle(lead.title());
        if (title.isBlank()) return Optional.empty();
        List<ClientReport.CitedText> summary = new ArrayList<>();
        summary.add(new ClientReport.CitedText(clip(sentence(title)), List.of(lead.referenceId())));
        for (PublicEvidence source : newestFirst) {
            String text = firstSentence(source.excerpt(), title);
            if (text != null) {
                summary.add(new ClientReport.CitedText(clip(text), List.of(source.referenceId())));
                break;
            }
        }

        String headline = clip(title, 300);
        StringBuilder everything = new StringBuilder(headline);
        summary.forEach(item -> everything.append(' ').append(item.text()));
        if (ClientReportValidator.containsAdvice(everything.toString())) return Optional.empty();

        List<String> refs = summary.stream().flatMap(item -> item.sourceRefs().stream()).distinct().toList();
        return Optional.of(new ClientReport(headline, summary, List.of(), List.of(), null, List.of(), "", refs));
    }

    /** "Apple falls on earnings - CNBC" becomes "Apple falls on earnings". */
    static String cleanTitle(String title) {
        String cleaned = title == null ? "" : title.strip();
        Matcher suffix = PUBLISHER_SUFFIX.matcher(cleaned);
        if (suffix.find() && suffix.start() > 20) cleaned = cleaned.substring(0, suffix.start());
        return cleaned.strip();
    }

    private static String sentence(String text) {
        String stripped = text.strip();
        return stripped.matches(".*[.!?]$") ? stripped : stripped + ".";
    }

    /** The first full sentence of the source's text, unless it only repeats the title. */
    private static String firstSentence(String excerpt, String title) {
        if (excerpt == null) return null;
        String text = excerpt.replaceAll("\\s+", " ").strip();
        Matcher found = FIRST_SENTENCE.matcher(text);
        if (!found.find()) return null;
        String first = found.group(1).strip();
        String plain = first.toLowerCase().replaceAll("[^a-z0-9]+", " ").strip();
        String plainTitle = title.toLowerCase().replaceAll("[^a-z0-9]+", " ").strip();
        if (plain.isEmpty() || plain.equals(plainTitle) || plainTitle.contains(plain) || plain.contains(plainTitle)) return null;
        return first;
    }

    private static String clip(String text) {
        return clip(text, MAX_SENTENCE);
    }

    private static String clip(String text, int maximum) {
        if (text.length() <= maximum) return text;
        return text.substring(0, maximum - 3).strip() + "...";
    }
}
