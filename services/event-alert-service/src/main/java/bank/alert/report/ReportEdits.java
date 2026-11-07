package bank.alert.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An agent's edit of a report (news-alert-drafts plan, sections 5.2 and 5.3). The agent changes only the wording of
 * the executive summary, key facts, why it matters, what to monitor and uncertainty. The headline, the sources and
 * which source each sentence cites never change, so every citation still points to one of the report's own sources.
 *
 * <p>The edited text is checked again with the rules the model's output meets: the same shape and lengths, every
 * figure the agent added appears in a source the sentence cites (or, for a sentence without citations, in one of the
 * report's sources), and no advice language. A failure names the section and the item.
 */
public final class ReportEdits {

    /** The edit request's sections, in the order the page shows them. */
    public static final List<String> SECTIONS = List.of("executiveSummary", "keyFacts", "whyItMatters", "whatToMonitor",
            "uncertainty");

    /** Advice the model's rules already refuse, and wording an agent might add. */
    static final List<String> ADVICE_PHRASES;
    /** Words refused only where the agent added them: "sell-off" or "buyback" in the sources stays allowed. */
    private static final Pattern ADDED_ADVICE = Pattern.compile("\\b(buy|sell|add to your position|top up|exit your position)\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    static {
        List<String> phrases = new ArrayList<>(ClientReportValidator.ADVICE_PHRASES);
        phrases.addAll(List.of("add to your position", "we advise", "we suggest buying", "we suggest selling"));
        ADVICE_PHRASES = List.copyOf(phrases);
    }

    /** What failed, and where: {@code index} is the item within the section, from 0, or -1 for the whole section. */
    public record Violation(String section, int index, String code, String message) {
    }

    /** A refused edit. Nothing is saved. */
    public static final class EditRefused extends RuntimeException {
        private final transient Violation violation;

        public EditRefused(Violation violation) {
            super(violation.message());
            this.violation = violation;
        }

        public Violation violation() {
            return violation;
        }
    }

    /** The text of one source the report cites: its title and excerpt, as the model saw them. */
    public record SourceText(String title, String excerpt) {
    }

    private ReportEdits() {
    }

    /** Whether this stored report can be edited: older migrated briefings have no sections to edit. */
    public static boolean editable(String schemaVersion) {
        return ClientReport.SCHEMA_VERSION.equals(schemaVersion);
    }

    /**
     * The report with the agent's wording in place of the original's.
     *
     * @throws EditRefused the sections do not fit the report (for example a missing or extra item)
     */
    public static ObjectNode merge(JsonNode original, JsonNode sections) {
        if (sections == null || !sections.isObject()) {
            throw refused("executiveSummary", -1, "INVALID_EDIT", "The edit is empty");
        }
        ObjectNode merged = original.deepCopy();

        ArrayNode summary = (ArrayNode) merged.withArray("executiveSummary");
        List<String> summaryText = strings(sections, "executiveSummary", summary.size(), summary.size());
        for (int i = 0; i < summary.size(); i++) ((ObjectNode) summary.get(i)).put("text", summaryText.get(i));

        ArrayNode facts = (ArrayNode) merged.withArray("keyFacts");
        JsonNode editedFacts = sections.path("keyFacts");
        if (!editedFacts.isArray() || editedFacts.size() != facts.size()) {
            throw refused("keyFacts", -1, "INVALID_EDIT", "Key facts must keep the same number of facts");
        }
        for (int i = 0; i < facts.size(); i++) {
            ObjectNode fact = (ObjectNode) facts.get(i);
            JsonNode edited = editedFacts.get(i);
            fact.put("label", text(edited, "label", "keyFacts", i));
            fact.put("value", text(edited, "value", "keyFacts", i));
            fact.put("context", text(edited, "context", "keyFacts", i));
        }

        ArrayNode why = (ArrayNode) merged.withArray("whyItMatters");
        List<String> whyText = strings(sections, "whyItMatters", why.size(), why.size());
        for (int i = 0; i < why.size(); i++) ((ObjectNode) why.get(i)).put("text", whyText.get(i));

        List<String> monitor = strings(sections, "whatToMonitor", 2, 4);
        ArrayNode monitorNode = merged.putArray("whatToMonitor");
        monitor.forEach(monitorNode::add);

        JsonNode uncertainty = sections.path("uncertainty");
        if (!uncertainty.isTextual()) throw refused("uncertainty", -1, "INVALID_EDIT", "Uncertainty is missing");
        merged.put("uncertainty", uncertainty.asText().strip());
        return merged;
    }

    /** The sections of a stored report, in the shape an edit takes: what the page fills its boxes from. */
    public static ObjectNode sectionsOf(JsonNode report, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        ObjectNode sections = mapper.createObjectNode();
        ArrayNode summary = sections.putArray("executiveSummary");
        report.path("executiveSummary").forEach(item -> summary.add(item.path("text").asText()));
        ArrayNode facts = sections.putArray("keyFacts");
        report.path("keyFacts").forEach(item -> facts.addObject().put("label", item.path("label").asText())
                .put("value", item.path("value").asText()).put("context", item.path("context").asText()));
        ArrayNode why = sections.putArray("whyItMatters");
        report.path("whyItMatters").forEach(item -> why.add(item.path("text").asText()));
        ArrayNode monitor = sections.putArray("whatToMonitor");
        report.path("whatToMonitor").forEach(item -> monitor.add(item.asText()));
        sections.put("uncertainty", report.path("uncertainty").asText(""));
        return sections;
    }

    /**
     * Checks the edited report against the original. Returns null when it may be saved.
     *
     * @param sources the report's sources by reference id
     */
    public static Violation check(JsonNode original, JsonNode edited, Map<String, SourceText> sources) {
        // Shape and length, the same limits the model's output meets.
        Violation shape = shape(edited);
        if (shape != null) return shape;

        String allSources = haystack(sources.values());
        JsonNode summary = edited.path("executiveSummary");
        for (int i = 0; i < summary.size(); i++) {
            Violation v = item("executiveSummary", i, original.path("executiveSummary").path(i).path("text").asText(""),
                    summary.get(i).path("text").asText(), refs(summary.get(i)), sources, allSources);
            if (v != null) return v;
        }
        JsonNode facts = edited.path("keyFacts");
        for (int i = 0; i < facts.size(); i++) {
            JsonNode before = original.path("keyFacts").path(i);
            JsonNode after = facts.get(i);
            Violation v = item("keyFacts", i, factText(before), factText(after), refs(after), sources, allSources);
            if (v != null) return v;
        }
        JsonNode why = edited.path("whyItMatters");
        for (int i = 0; i < why.size(); i++) {
            JsonNode after = why.get(i);
            List<String> cited = "FACT".equals(after.path("kind").asText()) ? refs(after) : List.of();
            Violation v = item("whyItMatters", i, original.path("whyItMatters").path(i).path("text").asText(""),
                    after.path("text").asText(), cited, sources, allSources);
            if (v != null) return v;
        }
        JsonNode monitor = edited.path("whatToMonitor");
        List<String> originalMonitor = new ArrayList<>();
        original.path("whatToMonitor").forEach(item -> originalMonitor.add(item.asText()));
        for (int i = 0; i < monitor.size(); i++) {
            String before = i < originalMonitor.size() ? originalMonitor.get(i) : String.join(" ", originalMonitor);
            Violation v = item("whatToMonitor", i, before, monitor.get(i).asText(), List.of(), sources, allSources);
            if (v != null) return v;
        }
        return item("uncertainty", -1, original.path("uncertainty").asText(""), edited.path("uncertainty").asText(),
                List.of(), sources, allSources);
    }

    private static Violation shape(JsonNode report) {
        JsonNode summary = report.path("executiveSummary");
        if (summary.size() < 2 || summary.size() > 3) return v("executiveSummary", -1, "INVALID_SHAPE", "The summary needs 2 or 3 sentences");
        for (int i = 0; i < summary.size(); i++) {
            Violation v = length("executiveSummary", i, summary.get(i).path("text").asText(), ClientReportValidator.MAX_TEXT);
            if (v != null) return v;
        }
        JsonNode facts = report.path("keyFacts");
        for (int i = 0; i < facts.size(); i++) {
            JsonNode fact = facts.get(i);
            Violation v = length("keyFacts", i, fact.path("label").asText(), 100);
            if (v == null) v = length("keyFacts", i, fact.path("value").asText(), 200);
            if (v == null) v = length("keyFacts", i, fact.path("context").asText(), ClientReportValidator.MAX_TEXT);
            if (v != null) return v;
        }
        JsonNode why = report.path("whyItMatters");
        for (int i = 0; i < why.size(); i++) {
            Violation v = length("whyItMatters", i, why.get(i).path("text").asText(), ClientReportValidator.MAX_TEXT);
            if (v != null) return v;
        }
        JsonNode monitor = report.path("whatToMonitor");
        if (monitor.size() < 2 || monitor.size() > 4) return v("whatToMonitor", -1, "INVALID_SHAPE", "List 2 to 4 things to monitor");
        for (int i = 0; i < monitor.size(); i++) {
            Violation v = length("whatToMonitor", i, monitor.get(i).asText(), 300);
            if (v != null) return v;
        }
        return length("uncertainty", -1, report.path("uncertainty").asText(), 500);
    }

    private static Violation length(String section, int index, String text, int maximum) {
        if (text == null || text.isBlank()) return v(section, index, "EMPTY_TEXT", "This text cannot be empty");
        if (text.length() > maximum) return v(section, index, "TEXT_TOO_LONG", "This text is longer than " + maximum + " characters");
        return null;
    }

    /** One item: no advice, and every figure the agent added appears in the sources it may rely on. */
    private static Violation item(String section, int index, String before, String after, List<String> cited,
            Map<String, SourceText> sources, String allSources) {
        String lower = after.toLowerCase(Locale.ROOT);
        String lowerBefore = before.toLowerCase(Locale.ROOT);
        for (String phrase : ADVICE_PHRASES) {
            if (lower.contains(phrase)) {
                return v(section, index, "ADVICE_LANGUAGE", "\"" + phrase + "\" reads as investment advice");
            }
        }
        Matcher advice = ADDED_ADVICE.matcher(lower);
        while (advice.find()) {
            if (!lowerBefore.contains(advice.group())) {
                return v(section, index, "ADVICE_LANGUAGE", "\"" + advice.group() + "\" reads as investment advice");
            }
        }
        if (after.equals(before)) return null;
        List<String> beforeNumbers = numbers(before);
        String haystack = cited.isEmpty() ? allSources
                : haystack(cited.stream().map(sources::get).filter(java.util.Objects::nonNull).toList());
        for (String figure : numbers(after)) {
            if (beforeNumbers.contains(figure)) continue;
            Pattern whole = Pattern.compile("(?<![\\d.])" + Pattern.quote(figure) + "(?![\\d])");
            if (!whole.matcher(haystack).find()) {
                return v(section, index, "UNSUPPORTED_FIGURE",
                        "The figure " + figure + " is not in " + (cited.isEmpty() ? "the report's sources" : "the sources this sentence cites"));
            }
        }
        return null;
    }

    private static String haystack(java.util.Collection<SourceText> texts) {
        StringBuilder all = new StringBuilder();
        for (SourceText text : texts) {
            all.append(' ').append(text.title() == null ? "" : text.title()).append(' ')
                    .append(text.excerpt() == null ? "" : text.excerpt());
        }
        return all.toString().toLowerCase(Locale.ROOT).replaceAll("(?<=\\d),(?=\\d)", "");
    }

    static List<String> numbers(String value) {
        List<String> figures = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(value == null ? "" : value.toLowerCase(Locale.ROOT));
        while (matcher.find()) figures.add(matcher.group().replace(",", ""));
        return figures;
    }

    private static String factText(JsonNode fact) {
        return fact.path("label").asText("") + " " + fact.path("value").asText("") + " " + fact.path("context").asText("");
    }

    private static List<String> refs(JsonNode item) {
        List<String> refs = new ArrayList<>();
        item.path("sourceRefs").forEach(ref -> refs.add(ref.asText()));
        return refs;
    }

    private static List<String> strings(JsonNode sections, String section, int min, int max) {
        JsonNode node = sections.path(section);
        if (!node.isArray() || node.size() < min || node.size() > max) {
            throw refused(section, -1, "INVALID_EDIT", min == max
                    ? "This section must keep " + min + " item" + (min == 1 ? "" : "s")
                    : "This section needs " + min + " to " + max + " items");
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            if (!node.get(i).isTextual()) throw refused(section, i, "INVALID_EDIT", "This item must be text");
            values.add(node.get(i).asText().strip());
        }
        return values;
    }

    private static String text(JsonNode node, String field, String section, int index) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) throw refused(section, index, "INVALID_EDIT", "The " + field + " is missing");
        return value.asText().strip();
    }

    private static Violation v(String section, int index, String code, String message) {
        return new Violation(section, index, code, message);
    }

    private static EditRefused refused(String section, int index, String code, String message) {
        return new EditRefused(new Violation(section, index, code, message));
    }
}
