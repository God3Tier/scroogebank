package bank.engagement.drafting;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Checks model output before it becomes a draft (ADR-0054 decision 4). Output
 * that fails any check is dropped and the template is used instead. The
 * reason codes are for logs and metrics, never shown to the agent.
 */
@Component
public class DraftSafetyScreen {

    /** Raised whenever the block list changes. */
    static final String BLOCKLIST_VERSION = "v1";

    static final int MAX_SUBJECT = 120;
    static final int MAX_BODY = 1_500;

    private static final Pattern BLOCKED_TERMS = Pattern.compile(
            "\\b(rates?|interest|returns?|yields?|invest\\w*|guarantee\\w*|promotions?|promo|offers?|loans?"
                    + "|credit\\w*|funds?|stocks?|shares|insurance|insure\\w*|accounts?|balances?|transactions?"
                    + "|deposits?|fees?|prices?|discounts?|bonus\\w*)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile(
            "(https?://|www\\.)|\\b[a-z0-9-]+\\.(com|sg|net|org|io|co|biz|info|me|ly)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s()-]{6,}\\d");
    private static final Pattern FIGURE = Pattern.compile(
            "\\d\\s*%|%\\s*\\d|(\\$|S\\$|US\\$|€|£|¥)\\s*\\d|\\b(SGD|USD|EUR|GBP|MYR|CNY|JPY)\\s*\\d"
                    + "|\\d\\s*(SGD|USD|EUR|GBP|MYR|CNY|JPY|dollars?)\\b|\\bper\\s*cent\\b|\\bpercent\\b",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper strict;

    public DraftSafetyScreen(ObjectMapper mapper) {
        this.strict = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public Result inspect(String modelOutput, DraftPurpose purpose) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return Result.rejected("empty_output");
        }
        ModelDraft draft;
        try {
            draft = strict.readValue(stripFence(modelOutput.strip()), ModelDraft.class);
        } catch (Exception ex) {
            return Result.rejected("invalid_json");
        }
        if (draft.subject() == null || draft.body() == null) {
            return Result.rejected("invalid_json");
        }
        String subject = draft.subject().strip();
        String body = draft.body().strip();
        return check(subject, body, purpose);
    }

    /**
     * The checks on a subject and body, also applied to every template in
     * tests. A follow-up from another feature has exactly one details slot,
     * in the body; any other purpose has none (ADR-0068 decision 2).
     */
    Result check(String subject, String body, DraftPurpose purpose) {
        if (subject.isEmpty() || subject.length() > MAX_SUBJECT || body.isEmpty() || body.length() > MAX_BODY) {
            return Result.rejected("length");
        }
        if (!body.contains(Placeholders.CLIENT_FIRST_NAME_TOKEN)) {
            return Result.rejected("missing_placeholder");
        }
        if (!Placeholders.onlyAllowed(subject) || !Placeholders.onlyAllowed(body)
                || subject.contains(Placeholders.AGENT_DETAILS_TOKEN)) {
            return Result.rejected("unknown_placeholder");
        }
        int slots = body.split(Pattern.quote(Placeholders.AGENT_DETAILS_TOKEN), -1).length - 1;
        if (slots != (purpose.hasDetailsSlot() ? 1 : 0)) {
            return Result.rejected(purpose.hasDetailsSlot() ? "missing_details_slot" : "unknown_placeholder");
        }
        String text = subject + "\n" + body;
        if (URL.matcher(text).find()) return Result.rejected("url");
        if (EMAIL.matcher(text).find()) return Result.rejected("email");
        if (PHONE.matcher(text).find()) return Result.rejected("phone");
        if (FIGURE.matcher(text).find()) return Result.rejected("figure");
        if (BLOCKED_TERMS.matcher(text).find()) return Result.rejected("blocked_term");
        return Result.accepted(subject, body);
    }

    /** Models sometimes wrap JSON in a Markdown fence; nothing else around it is accepted. */
    private static String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int closing = text.lastIndexOf("```");
        if (firstNewline < 0 || closing <= firstNewline) {
            return text;
        }
        return text.substring(firstNewline + 1, closing).strip();
    }

    record ModelDraft(String subject, String body) {
        @JsonCreator
        ModelDraft(@JsonProperty("subject") String subject, @JsonProperty("body") String body) {
            this.subject = subject;
            this.body = body;
        }
    }

    public record Result(boolean accepted, String subject, String body, String reason) {

        static Result accepted(String subject, String body) {
            return new Result(true, subject, body, null);
        }

        static Result rejected(String reason) {
            return new Result(false, null, null, reason);
        }
    }
}
