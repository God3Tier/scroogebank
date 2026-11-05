package bank.engagement.reports;

import bank.engagement.integration.sources.ReportDeliveries.Delivery;
import bank.engagement.integration.sources.ReportDeliveries.Source;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes one client email from an approved report (ADR-0084). Everything in it comes from the validated report or
 * from fixed application text: the portfolio line, the disclaimer and the relationship manager details. No model
 * runs here and nothing is added to what the agent approved. Each cited sentence carries the numbers of its
 * sources, listed at the end.
 */
final class ReportEmailRenderer {

    private ReportEmailRenderer() {
    }

    record Rendered(String subject, String body) {
    }

    static Rendered render(Delivery delivery, String clientFirstName, String agentName, String agentEmail) {
        JsonNode report = delivery.report();
        List<Source> sources = delivery.sources();
        StringBuilder out = new StringBuilder();
        out.append("Dear ").append(clientFirstName == null || clientFirstName.isBlank() ? "client" : clientFirstName.strip())
                .append(",\n\n");

        List<String> summary = new ArrayList<>();
        report.path("executiveSummary").forEach(item -> summary.add(cited(item.path("text").asText(), item.path("sourceRefs"), sources)));
        out.append(String.join(" ", summary)).append("\n");

        if (report.path("keyFacts").size() > 0) {
            out.append("\nKey facts\n");
            report.path("keyFacts").forEach(fact -> out.append("- ").append(fact.path("label").asText()).append(": ")
                    .append(fact.path("value").asText()).append(". ")
                    .append(cited(fact.path("context").asText(), fact.path("sourceRefs"), sources)).append("\n"));
        }
        if (report.path("whyItMatters").size() > 0) {
            out.append("\nWhy it matters\n");
            report.path("whyItMatters").forEach(item -> {
                boolean potential = "POTENTIAL_IMPLICATION".equals(item.path("kind").asText());
                out.append("- ").append(potential ? "Potential implication: " : "")
                        .append(cited(item.path("text").asText(), item.path("sourceRefs"), sources)).append("\n");
            });
        }
        if (report.path("whatToMonitor").size() > 0) {
            out.append("\nWhat to monitor\n");
            report.path("whatToMonitor").forEach(item -> out.append("- ").append(item.asText()).append("\n"));
        }
        if (report.hasNonNull("uncertainty")) {
            out.append("\nUncertainty: ").append(report.get("uncertainty").asText()).append("\n");
        }
        if (!sources.isEmpty()) {
            out.append("\nSources\n");
            for (int i = 0; i < sources.size(); i++) {
                out.append("[").append(i + 1).append("] ").append(sources.get(i).title()).append(" - ")
                        .append(sources.get(i).url()).append("\n");
            }
        }
        out.append("\n").append(delivery.portfolioLine()).append("\n\n").append(delivery.disclaimer()).append("\n\n")
                .append("Your relationship manager: ").append(agentName).append(", ").append(agentEmail).append("\n");
        String headline = report.path("headline").asText("Market update");
        return new Rendered(headline.length() > 120 ? headline.substring(0, 120) : headline, out.toString());
    }

    private static String cited(String text, JsonNode refs, List<Source> sources) {
        StringBuilder marks = new StringBuilder();
        refs.forEach(ref -> {
            for (int i = 0; i < sources.size(); i++) {
                if (sources.get(i).id().equals(ref.asText())) {
                    marks.append(" [").append(i + 1).append("]");
                }
            }
        });
        return text + marks;
    }
}
