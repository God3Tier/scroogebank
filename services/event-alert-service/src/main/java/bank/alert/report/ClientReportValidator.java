package bank.alert.report;

import bank.alert.ai.PublicEvidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules a model's report must meet before anyone sees it (ADR-0084). A report that breaks any of them is
 * refused outright and never repaired: every factual claim cites the supplied evidence, every figure in a key
 * fact appears in a cited title or excerpt, there is no market data, no advice language and no wrong shape.
 */
public final class ClientReportValidator {

    public static final int MAX_TEXT = 600;

    /** Phrases that turn a summary into advice. Checked in everything the model wrote. */
    static final List<String> ADVICE_PHRASES = List.of(
            "you should", "we recommend", "recommend buying", "recommend selling", "should buy", "should sell",
            "consider buying", "consider selling", "must buy", "must sell", "guaranteed return", "guaranteed profit");

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private ClientReportValidator() {
    }

    /** Returns the reason code if the output must be refused, or null when it is acceptable. */
    public static String validate(ClientReportModelOutput output, List<PublicEvidence> evidence) {
        if (output.abstain() == null) return "INVALID_MODEL_SCHEMA";
        if (Boolean.TRUE.equals(output.abstain())) {
            return output.abstentionReason() == null || output.abstentionReason().isBlank() ? "INVALID_ABSTENTION" : null;
        }
        if (blankOrLong(output.headline(), 300) || blankOrLong(output.uncertainty(), 500)) return "INVALID_MODEL_SCHEMA";
        if (output.marketContext() != null) return "MARKET_CONTEXT_NOT_ALLOWED";
        if (size(output.executiveSummary()) < 2 || size(output.executiveSummary()) > 3) return "INVALID_MODEL_SCHEMA";
        if (output.keyFacts() == null || output.keyFacts().size() > 4) return "INVALID_MODEL_SCHEMA";
        if (size(output.whyItMatters()) < 1 || size(output.whyItMatters()) > 4) return "INVALID_MODEL_SCHEMA";
        if (size(output.whatToMonitor()) < 2 || size(output.whatToMonitor()) > 4) return "INVALID_MODEL_SCHEMA";
        if (output.sourceRefs() == null || output.sourceRefs().isEmpty()) return "INVALID_MODEL_SCHEMA";

        Map<String, PublicEvidence> byRef = references(evidence);
        for (ClientReport.CitedText sentence : output.executiveSummary()) {
            if (sentence == null || blankOrLong(sentence.text(), MAX_TEXT)) return "INVALID_MODEL_SCHEMA";
            String reason = cited(sentence.sourceRefs(), byRef);
            if (reason != null) return reason;
        }
        for (ClientReport.Implication implication : output.whyItMatters()) {
            if (implication == null || implication.kind() == null || blankOrLong(implication.text(), MAX_TEXT)) {
                return "INVALID_MODEL_SCHEMA";
            }
            if (implication.kind() == ClientReport.Kind.FACT) {
                String reason = cited(implication.sourceRefs(), byRef);
                if (reason != null) return reason;
            } else if (implication.sourceRefs() != null && !byRef.keySet().containsAll(implication.sourceRefs())) {
                return "MISSING_OR_UNKNOWN_CITATION";
            }
        }
        for (ClientReport.KeyFact fact : output.keyFacts()) {
            if (fact == null || blankOrLong(fact.label(), 100) || blankOrLong(fact.value(), 200)
                    || blankOrLong(fact.context(), MAX_TEXT)) return "INVALID_MODEL_SCHEMA";
            String reason = cited(fact.sourceRefs(), byRef);
            if (reason != null) return reason;
            if (!figuresAppearIn(fact, byRef)) return "UNSUPPORTED_FIGURE";
        }
        for (String item : output.whatToMonitor()) {
            if (blankOrLong(item, 300)) return "INVALID_MODEL_SCHEMA";
        }
        if (!byRef.keySet().containsAll(output.sourceRefs())) return "MISSING_OR_UNKNOWN_CITATION";
        if (adviceLanguage(output)) return "ADVICE_LANGUAGE_DETECTED";
        return null;
    }

    /** Builds the stored report: citations by URL are turned into the sources' reference ids. */
    /** Some models wrap a string in quotes, or in {@code ="..."}; the wrapping is not part of the text. */
    static String tidy(String text) {
        String trimmed = text.strip();
        if (trimmed.length() > 3 && trimmed.startsWith("=\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(2, trimmed.length() - 1).strip();
        }
        if (trimmed.length() > 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    public static ClientReport accepted(ClientReportModelOutput output, List<PublicEvidence> evidence) {
        Map<String, PublicEvidence> byRef = references(evidence);
        List<ClientReport.CitedText> summary = output.executiveSummary().stream()
                .map(sentence -> new ClientReport.CitedText(tidy(sentence.text()), ids(sentence.sourceRefs(), byRef))).toList();
        List<ClientReport.KeyFact> facts = output.keyFacts().stream()
                .map(fact -> new ClientReport.KeyFact(tidy(fact.label()), tidy(fact.value()), tidy(fact.context()),
                        ids(fact.sourceRefs(), byRef))).toList();
        List<ClientReport.Implication> why = output.whyItMatters().stream()
                .map(item -> new ClientReport.Implication(tidy(item.text()), item.kind(),
                        ids(item.sourceRefs() == null ? List.of() : item.sourceRefs(), byRef))).toList();
        return new ClientReport(tidy(output.headline()), summary, facts, why, null,
                output.whatToMonitor().stream().map(ClientReportValidator::tidy).toList(), tidy(output.uncertainty()),
                ids(output.sourceRefs(), byRef));
    }

    /** A source may be cited by its reference id or by its URL; both name the same source. */
    private static Map<String, PublicEvidence> references(List<PublicEvidence> evidence) {
        Map<String, PublicEvidence> byRef = new LinkedHashMap<>();
        for (PublicEvidence item : evidence) {
            byRef.put(item.referenceId(), item);
            byRef.put(item.canonicalUrl(), item);
        }
        return byRef;
    }

    private static List<String> ids(List<String> refs, Map<String, PublicEvidence> byRef) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String ref : refs) ids.add(byRef.get(ref).referenceId());
        return new ArrayList<>(ids);
    }

    private static String cited(List<String> refs, Map<String, PublicEvidence> byRef) {
        if (refs == null || refs.isEmpty() || !byRef.keySet().containsAll(refs)) return "MISSING_OR_UNKNOWN_CITATION";
        return null;
    }

    /** Every number in the fact's value must appear in a source the fact cites, after dropping thousands separators. */
    static boolean figuresAppearIn(ClientReport.KeyFact fact, Map<String, PublicEvidence> byRef) {
        List<String> figures = numbers(fact.value());
        if (figures.isEmpty()) return true;
        StringBuilder text = new StringBuilder();
        for (String ref : fact.sourceRefs()) {
            PublicEvidence evidence = byRef.get(ref);
            text.append(' ').append(evidence.title()).append(' ').append(evidence.excerpt());
        }
        String haystack = text.toString().toLowerCase(Locale.ROOT).replaceAll("(?<=\\d),(?=\\d)", "");
        for (String figure : figures) {
            Pattern whole = Pattern.compile("(?<![\\d.])" + Pattern.quote(figure) + "(?![\\d])");
            if (!whole.matcher(haystack).find()) return false;
        }
        return true;
    }

    static List<String> numbers(String value) {
        List<String> figures = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) figures.add(matcher.group().replace(",", ""));
        return figures;
    }

    private static boolean adviceLanguage(ClientReportModelOutput output) {
        StringBuilder all = new StringBuilder(output.headline()).append(' ').append(output.uncertainty());
        output.executiveSummary().forEach(sentence -> all.append(' ').append(sentence.text()));
        output.keyFacts().forEach(fact -> all.append(' ').append(fact.value()).append(' ').append(fact.context()));
        output.whyItMatters().forEach(item -> all.append(' ').append(item.text()));
        output.whatToMonitor().forEach(item -> all.append(' ').append(item));
        String combined = all.toString().toLowerCase(Locale.ROOT);
        return ADVICE_PHRASES.stream().anyMatch(combined::contains);
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static boolean blankOrLong(String value, int maximum) {
        return value == null || value.isBlank() || value.length() > maximum;
    }
}
