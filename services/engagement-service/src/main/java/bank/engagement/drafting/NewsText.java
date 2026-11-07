package bank.engagement.drafting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The text of a News Alert email (news-alert-drafts plan, section 5.5). The subject and summary are built from the
 * reports with no model call and the agent may edit them. The guide to the PDF and the fixed text are added at send
 * time, outside what the agent edits: the agent cannot remove or change them.
 */
final class NewsText {

    /** The same disclaimer event-alert-service puts on every report (ADR-0084, ReportText). */
    static final String DISCLAIMER = "This report is for information only. It is based on public sources, is not "
            + "investment advice, and does not take your personal circumstances into account.";

    private NewsText() {
    }

    /** One report's line in the summary and guide. */
    record Line(String issuer, String headline, String summaryLine) {
    }

    /** "Your portfolio update: Apple Inc, Shell plc and 2 more", cut to the subject column's 120 characters. */
    static String subject(List<Line> lines) {
        List<String> issuers = issuers(lines);
        String prefix = "Your portfolio update: ";
        if (issuers.isEmpty()) return "Your portfolio update";
        if (issuers.size() <= 2) {
            return cut(prefix + String.join(issuers.size() == 2 ? " and " : "", issuers), DraftService.MAX_SUBJECT);
        }
        int more = issuers.size() - 2;
        return cut(prefix + issuers.get(0) + ", " + issuers.get(1) + " and " + more + " more", DraftService.MAX_SUBJECT);
    }

    /**
     * The editable body: a greeting and one line per company, at most {@code maxLines}; the rest are named in the
     * guide below it.
     */
    static String summary(List<Line> lines, int maxLines) {
        List<String> issuers = issuers(lines);
        StringBuilder body = new StringBuilder("Dear {{client_first_name}},\n\n");
        body.append(issuers.size() == 1
                ? "Here is an update on " + issuers.getFirst() + ", a company in your portfolio."
                : "Here is an update on " + issuers.size() + " companies in your portfolio.");
        body.append(" The full report is attached.\n");
        int shown = Math.min(lines.size(), maxLines);
        for (int i = 0; i < shown; i++) {
            Line line = lines.get(i);
            String sentence = line.summaryLine() == null || line.summaryLine().isBlank() ? line.headline() : line.summaryLine();
            body.append("\n• ").append(safe(line.issuer())).append(": ").append(safe(sentence));
        }
        if (lines.size() > shown) {
            int rest = lines.size() - shown;
            body.append("\n… and ").append(rest).append(rest == 1 ? " more company, listed below." : " more companies, listed below.");
        }
        return cut(body.toString(), DraftService.MAX_BODY);
    }

    /** One page reference for the guide. {@code page} is null in the editor's preview, before the PDF exists. */
    record GuideLine(String issuer, String headline, Integer page) {
    }

    static String guide(String fileName, List<GuideLine> lines) {
        StringBuilder guide = new StringBuilder("Where to find each report in the attached PDF (").append(fileName).append("):");
        for (GuideLine line : lines) {
            guide.append("\n• ").append(safe(line.issuer())).append(": ").append(safe(line.headline()));
            if (line.page() != null) guide.append(". Page ").append(line.page());
        }
        return guide.toString();
    }

    /** The portfolio line, the disclaimer and the relationship manager's details, in this order and wording. */
    static String fixed(List<String> issuers, String agentName, String agentEmail) {
        return "Your portfolio has exposure to " + joined(issuers) + ", which may be affected by these events.\n\n"
                + DISCLAIMER + "\n\n"
                + "Your relationship manager: " + agentName + (agentEmail == null || agentEmail.isBlank() ? "" : ", " + agentEmail);
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<String> issuers(List<Line> lines) {
        List<String> issuers = new ArrayList<>();
        for (Line line : lines) {
            if (!issuers.contains(line.issuer())) issuers.add(line.issuer());
        }
        return issuers;
    }

    private static String joined(List<String> issuers) {
        if (issuers.size() <= 1) return issuers.isEmpty() ? "these companies" : issuers.getFirst();
        return String.join(", ", issuers.subList(0, issuers.size() - 1)) + " and " + issuers.getLast();
    }

    /** Report text never holds a placeholder: braces would stop the send. */
    private static String safe(String text) {
        return text == null ? "" : text.replace("{{", "(").replace("}}", ")").strip();
    }

    private static String cut(String text, int maximum) {
        if (text.length() <= maximum) return text;
        String cut = text.substring(0, maximum - 1);
        int space = cut.lastIndexOf(' ');
        return (space > maximum / 2 ? cut.substring(0, space) : cut).strip() + "…";
    }
}
