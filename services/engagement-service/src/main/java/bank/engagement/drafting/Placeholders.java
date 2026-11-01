package bank.engagement.drafting;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * The placeholders drafts may hold (ADR-0054 decision 2). The names are filled
 * in after generation, so the model never sees who the client is, and marked
 * again when an agent saves, so stored drafts hold no names (ADR-0056).
 *
 * <p>{@code {{agent_details}}} marks where the agent adds their own details to
 * a follow-up from another feature (ADR-0068 decision 2). It is shown as
 * {@link #DETAILS_PROMPT} and marked back the same way, but never filled for
 * sending, so an untouched slot stops the send.
 */
@UtilityClass
final class Placeholders {

    static final String CLIENT_FIRST_NAME = "client_first_name";
    static final String AGENT_NAME = "agent_name";
    static final String AGENT_DETAILS = "agent_details";
    static final Set<String> ALLOWED = Set.of(CLIENT_FIRST_NAME, AGENT_NAME, AGENT_DETAILS);

    static final String CLIENT_FIRST_NAME_TOKEN = "{{" + CLIENT_FIRST_NAME + "}}";
    static final String AGENT_NAME_TOKEN = "{{" + AGENT_NAME + "}}";
    static final String AGENT_DETAILS_TOKEN = "{{" + AGENT_DETAILS + "}}";

    /** What the agent sees, and replaces, where the details go. */
    static final String DETAILS_PROMPT = "[Add your details here]";

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^{}]*?)\\s*}}");

    /** Replaces each allowed placeholder that has a non-blank value; leaves the rest. */
    static String fill(String template, Map<String, String> values) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            String replacement = value == null || value.isBlank() ? matcher.group() : value.strip();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * The reverse of {@link #fill}: turns each whole-word, exact occurrence of a
     * value back into its placeholder, so agents edit plain text but drafts are
     * stored without names (ADR-0056). Longer values go first, so an agent
     * named "Synthetic Agent" is marked before a client named "Synthetic".
     */
    static String mark(String text, Map<String, String> values) {
        String marked = text;
        List<Map.Entry<String, String>> byLength = values.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getValue().strip().length())
                        .reversed())
                .toList();
        for (Map.Entry<String, String> entry : byLength) {
            Pattern word = Pattern.compile("(?<![\\p{L}\\p{N}_{])" + Pattern.quote(entry.getValue().strip())
                    + "(?![\\p{L}\\p{N}_}])");
            marked = word.matcher(marked).replaceAll(Matcher.quoteReplacement("{{" + entry.getKey() + "}}"));
        }
        return marked;
    }

    /** True when text still holds a placeholder or stray braces. */
    static boolean unresolved(String text) {
        return text.contains("{{") || text.contains("}}");
    }

    /** True when every placeholder in text is one of the two allowed. */
    static boolean onlyAllowed(String text) {
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            if (!ALLOWED.contains(matcher.group(1))) {
                return false;
            }
        }
        return !unresolved(TOKEN.matcher(text).replaceAll(""));
    }
}
