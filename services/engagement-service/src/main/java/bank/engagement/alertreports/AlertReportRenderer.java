package bank.engagement.alertreports;

import bank.engagement.integration.sources.ReportPackages.Source;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes one client email, in HTML and plain text, from the report the agent approved (ADR-0084). Everything in it
 * comes from the verified report, the fixed application text, or the agent's separately labelled note. No model runs
 * here, and nothing internal (scores, confidence, portfolio values, other clients) is available to put in it.
 */
final class AlertReportRenderer {

    static final String DISCLAIMER = "This report is for information only. It is based on public sources, is not "
            + "investment advice, and does not take your personal circumstances into account.";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'SGT'", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Singapore"));

    private AlertReportRenderer() {
    }

    record Rendered(String subject, String text, String html) {
    }

    static String portfolioLine(String issuer) {
        return "Your portfolio has exposure to " + issuer + ", which may be affected by this event.";
    }

    static Rendered render(JsonNode report, List<Source> sources, String issuer, String clientFirstName, String agentName,
            String agentEmail, String agentNote) {
        String greeting = "Dear " + (clientFirstName == null || clientFirstName.isBlank() ? "client" : clientFirstName.strip()) + ",";
        String headline = report.path("headline").asText("Company event");
        List<String> summary = new ArrayList<>();
        report.path("executiveSummary").forEach(s -> summary.add(cited(s.path("text").asText(), s.path("sourceRefs"), sources)));

        Doc doc = new Doc();
        doc.line("PORTFOLIO INTELLIGENCE / COMPANY EVENT", Doc.Kind.KICKER);
        doc.line(headline, Doc.Kind.TITLE);
        doc.line(greeting, Doc.Kind.PLAIN);
        doc.line(String.join(" ", summary), Doc.Kind.PLAIN);

        if (report.path("keyFacts").size() > 0) {
            doc.line("Key facts and numbers", Doc.Kind.HEADING);
            report.path("keyFacts").forEach(f -> doc.bullet(f.path("label").asText() + ": " + f.path("value").asText() + ". "
                    + cited(f.path("context").asText(), f.path("sourceRefs"), sources)));
        }
        if (report.path("whyItMatters").size() > 0) {
            doc.line("Why it matters", Doc.Kind.HEADING);
            report.path("whyItMatters").forEach(i -> doc.bullet(
                    ("POTENTIAL_IMPLICATION".equals(i.path("kind").asText()) ? "Potential implication: " : "")
                            + cited(i.path("text").asText(), i.path("sourceRefs"), sources)));
        }
        if (report.hasNonNull("marketContext") && !report.get("marketContext").asText().isBlank()) {
            doc.line("Market context", Doc.Kind.HEADING);
            doc.line(report.get("marketContext").asText(), Doc.Kind.PLAIN);
        }
        if (report.path("whatToMonitor").size() > 0) {
            doc.line("What to monitor", Doc.Kind.HEADING);
            report.path("whatToMonitor").forEach(i -> doc.bullet(i.asText()));
        }
        if (report.hasNonNull("uncertainty") && !report.get("uncertainty").asText().isBlank()) {
            doc.line("Uncertainty: " + report.get("uncertainty").asText(), Doc.Kind.PLAIN);
        }

        doc.line("Why you received this", Doc.Kind.HEADING);
        doc.line(portfolioLine(issuer), Doc.Kind.PLAIN);
        if (agentNote != null && !agentNote.isBlank()) {
            doc.line("Note from " + agentName + " (not part of the verified report)", Doc.Kind.LABEL);
            doc.line(agentNote.strip(), Doc.Kind.PLAIN);
        }

        if (!sources.isEmpty()) {
            doc.line("Linked sources", Doc.Kind.HEADING);
            for (int i = 0; i < sources.size(); i++) {
                Source s = sources.get(i);
                doc.source("[" + (i + 1) + "] " + s.title(), s.url(), "Published " + stamp(s.publishedAt())
                        + "; retrieved " + stamp(s.retrievedAt()));
            }
        }
        doc.line(DISCLAIMER, Doc.Kind.SMALL);
        doc.line("Your relationship manager: " + agentName + ", " + agentEmail + ". Reply to this email to reach them.", Doc.Kind.SMALL);

        String subject = headline.length() > 120 ? headline.substring(0, 120) : headline;
        return new Rendered(subject, doc.text(), doc.html());
    }

    private static String stamp(Instant instant) {
        return instant == null ? "date not supplied" : STAMP.format(instant);
    }

    private static String cited(String text, JsonNode refs, List<Source> sources) {
        StringBuilder marks = new StringBuilder();
        refs.forEach(ref -> {
            for (int i = 0; i < sources.size(); i++) {
                if (sources.get(i).id() != null && sources.get(i).id().equals(ref.asText())) {
                    marks.append(" [").append(i + 1).append("]");
                }
            }
        });
        return text + marks;
    }

    /** Collects the same content as plain text and as escaped HTML. */
    private static final class Doc {
        enum Kind { KICKER, TITLE, HEADING, LABEL, PLAIN, SMALL }

        private final StringBuilder text = new StringBuilder();
        private final StringBuilder html = new StringBuilder(
                "<html><body style=\"font-family:Arial,Helvetica,sans-serif;color:#1f2933;max-width:640px;margin:0 auto;padding:16px\">");
        private boolean inList;

        void line(String value, Kind kind) {
            closeList();
            String safe = HtmlUtils.htmlEscape(value);
            switch (kind) {
                case KICKER -> {
                    text.append(value).append("\n\n");
                    html.append("<p style=\"font-size:12px;letter-spacing:1px;color:#52606d;margin:0 0 8px\">").append(safe).append("</p>");
                }
                case TITLE -> {
                    text.append(value).append("\n\n");
                    html.append("<h1 style=\"font-size:22px;margin:0 0 16px\">").append(safe).append("</h1>");
                }
                case HEADING -> {
                    text.append("\n").append(value).append("\n");
                    html.append("<h2 style=\"font-size:16px;margin:20px 0 6px\">").append(safe).append("</h2>");
                }
                case LABEL -> {
                    text.append("\n").append(value).append("\n");
                    html.append("<p style=\"font-size:13px;font-weight:bold;margin:12px 0 2px\">").append(safe).append("</p>");
                }
                case SMALL -> {
                    text.append("\n").append(value).append("\n");
                    html.append("<p style=\"font-size:12px;color:#52606d;margin:12px 0\">").append(safe).append("</p>");
                }
                default -> {
                    text.append(value).append("\n");
                    html.append("<p style=\"margin:0 0 10px;line-height:1.5\">").append(safe).append("</p>");
                }
            }
        }

        void bullet(String value) {
            if (!inList) {
                html.append("<ul style=\"margin:0 0 10px;padding-left:20px;line-height:1.5\">");
                inList = true;
            }
            text.append("- ").append(value).append("\n");
            html.append("<li>").append(HtmlUtils.htmlEscape(value)).append("</li>");
        }

        void source(String title, String url, String when) {
            if (!inList) {
                html.append("<ul style=\"margin:0 0 10px;padding-left:20px;line-height:1.5\">");
                inList = true;
            }
            text.append(title).append("\n    ").append(url).append("\n    ").append(when).append("\n");
            html.append("<li>").append(HtmlUtils.htmlEscape(title)).append("<br><a href=\"")
                    .append(HtmlUtils.htmlEscape(url)).append("\">").append(HtmlUtils.htmlEscape(url))
                    .append("</a><br><span style=\"font-size:12px;color:#52606d\">").append(HtmlUtils.htmlEscape(when))
                    .append("</span></li>");
        }

        private void closeList() {
            if (inList) {
                html.append("</ul>");
                inList = false;
            }
        }

        String text() {
            return text.toString();
        }

        String html() {
            closeList();
            return html + "</body></html>";
        }
    }
}
