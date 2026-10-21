package bank.engagement.drafting;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two placeholders drafts may hold (ADR-0054 decision 2). They are filled
 * in after generation, so the model never sees who the client is.
 */
final class Placeholders {

    static final String CLIENT_FIRST_NAME = "client_first_name";
    static final String AGENT_NAME = "agent_name";
    static final Set<String> ALLOWED = Set.of(CLIENT_FIRST_NAME, AGENT_NAME);

    static final String CLIENT_FIRST_NAME_TOKEN = "{{" + CLIENT_FIRST_NAME + "}}";
    static final String AGENT_NAME_TOKEN = "{{" + AGENT_NAME + "}}";

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^{}]*?)\\s*}}");

    private Placeholders() {
    }

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
