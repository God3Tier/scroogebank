package bank.alert.event;

import bank.alert.api.EvidenceResponse;
import bank.alert.domain.AlertDecisionType;
import bank.alert.domain.AlertStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response shapes of the agent-event briefing (one card per agent and public event). */
public final class AgentEventModels {

    private AgentEventModels() {
    }

    /** The card an agent sees: one shared news summary and how many of their clients may be affected. */
    public record EventCard(
            UUID eventId,
            String headline,
            String summary,
            boolean aiGenerated,
            String uncertainty,
            EvidenceResponse evidence,
            int affectedClientCount,
            int openClientCount,
            BigDecimal topScore,
            Instant latestAlertAt) {
    }

    public record EventPage(List<EventCard> items, String nextCursor) {
        public EventPage {
            items = List.copyOf(items);
        }
    }

    /** One affected client: the evidence behind it is shown on drill-down only. */
    public record AffectedClient(
            UUID alertId,
            String clientRef,
            String accountRef,
            String instrumentId,
            AlertStatus status,
            BigDecimal score,
            BigDecimal portfolioPercentage,
            String amountSgd,
            long version) {
    }

    /** A source the event rests on. {@code usedInReport} is true for the (at most five) the model was shown. */
    public record ReportSource(String title, String url, String domain, Instant publishedAt, boolean usedInReport) {
    }

    /**
     * The event's latest client report (ADR-0084): the validated model output, every source linked to the event, and
     * the fixed text the application adds. {@code portfolioLines} gives, per affected instrument, the line that
     * ties the report to a portfolio; it says nothing about what is held.
     */
    public record ReportView(UUID reportId, int version, int versionCount, String schemaVersion, JsonNode report,
            List<ReportSource> sources, Map<String, String> portfolioLines, String disclaimer, Instant createdAt,
            List<ReportRecipient> recipients) {
        public ReportView {
            sources = List.copyOf(sources);
            portfolioLines = Map.copyOf(portfolioLines);
            recipients = List.copyOf(recipients);
        }
    }

    /** A client the agent approved the report for, and where its email stands: APPROVED, SENDING or SENT. */
    public record ReportRecipient(UUID alertId, String status) {
    }

    public record EventDetail(EventCard card, List<AffectedClient> clients, ActionResult latestAction, ReportView report) {
        public EventDetail {
            clients = List.copyOf(clients);
        }
    }

    public record ActionMember(
            @NotNull UUID alertId,
            @Min(0) long expectedVersion) {
    }

    /** An explicit set of clients the agent chose; nothing is applied to anyone not listed. */
    public record ActionRequest(
            @NotNull AlertDecisionType decision,
            @Size(max = 500) String note,
            @NotNull @Size(min = 1, max = 200) List<@Valid @NotNull ActionMember> members) {
    }

    public enum MemberOutcome {
        APPLIED, STALE_VERSION, INVALID_TRANSITION, NOT_AUTHORISED
    }

    public record MemberResult(UUID alertId, MemberOutcome outcome, AlertStatus previousStatus, AlertStatus newStatus) {
    }

    /** What was recorded. Informing a client here records intent only: no message is sent. */
    public record ActionResult(
            UUID actionId,
            UUID eventId,
            AlertDecisionType decision,
            Instant recordedAt,
            List<MemberResult> members) {
        public ActionResult {
            members = List.copyOf(members);
        }
    }
}
