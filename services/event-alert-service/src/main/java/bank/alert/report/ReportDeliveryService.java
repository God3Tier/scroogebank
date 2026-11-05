package bank.alert.report;

import bank.alert.domain.AlertStatus;
import bank.alert.domain.AuditOutbox;
import bank.alert.event.AgentEventModels.AffectedClient;
import bank.alert.event.AgentEventModels.EventDetail;
import bank.alert.event.AgentEventQueryService;
import bank.alert.repository.AuditOutboxRepository;
import bank.alert.service.AlertMutationConflictException;
import bank.alert.service.AlertNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Approval and delivery state of a client report (ADR-0084). The assigned agent approves one report version for
 * chosen clients; only then can the engagement service read the report for that client and send it. Every step
 * re-checks that the alert is still the agent's, and a recipient is claimed before sending so a report goes to a
 * client at most once.
 */
@Service
public class ReportDeliveryService {

    static final Duration STALE_CLAIM = Duration.ofMinutes(2);
    private static final Pattern CRM_CLIENT = Pattern.compile("^[0-9a-fA-F-]{36}$");

    /** What the engagement service needs to write one client email. */
    public record DeliveryPackage(UUID reportId, UUID alertId, String clientRef, String instrumentId, String status,
            JsonNode report, List<SourceLink> sources, String portfolioLine, String disclaimer) {
    }

    /** A source the report cites, by the id its sourceRefs use. */
    public record SourceLink(String id, String title, String url, String domain) {
    }

    public record Recipient(UUID alertId, String status) {
    }

    private final JdbcTemplate jdbc;
    private final AgentEventQueryService events;
    private final AuditOutboxRepository auditOutbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ReportDeliveryService(JdbcTemplate jdbc, AgentEventQueryService events, AuditOutboxRepository auditOutbox,
            ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.auditOutbox = auditOutbox;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Approves the event current report for the chosen alerts. Repeating an approval changes nothing. */
    @Transactional
    public List<Recipient> approve(UUID eventId, String agent, UUID reportId, List<UUID> alertIds) {
        EventDetail detail = events.detail(eventId, agent);
        if (detail.report() == null || !detail.report().reportId().equals(reportId)) {
            throw new AlertMutationConflictException("The report changed since you looked; reload and review it again");
        }
        Set<UUID> chosen = new LinkedHashSet<>(alertIds);
        Instant now = clock.instant();
        for (UUID alertId : chosen) {
            AffectedClient client = detail.clients().stream().filter(c -> c.alertId().equals(alertId)).findFirst()
                    .orElseThrow(AlertNotFoundException::new);
            if (client.status() == AlertStatus.DISMISSED || !CRM_CLIENT.matcher(client.clientRef()).matches()) {
                throw new AlertMutationConflictException("This client cannot be emailed the report");
            }
            int added = jdbc.update("""
                    INSERT INTO event_report_recipient (report_id, alert_id, agent_subject, client_ref, status, approved_at)
                    VALUES (?, ?, ?, ?, 'APPROVED', ?) ON CONFLICT (report_id, alert_id) DO NOTHING
                    """, reportId, alertId, agent, client.clientRef(), Timestamp.from(now));
            if (added > 0) {
                audit("alert.report-approved", reportId + "|" + alertId, Map.of("publicEventId", eventId,
                        "reportId", reportId, "alertId", alertId, "approvedBy", agent), now);
            }
        }
        return recipients(reportId, agent);
    }

    /** The approved report for one client, or not found if this agent did not approve it for them. */
    @Transactional(readOnly = true)
    public DeliveryPackage delivery(UUID reportId, UUID alertId, String agent) {
        Row row = row(reportId, alertId, agent);
        EventDetail detail = events.detail(row.eventId(), agent);
        AffectedClient client = detail.clients().stream().filter(c -> c.alertId().equals(alertId)).findFirst()
                .orElseThrow(AlertNotFoundException::new);
        try {
            String issuer = jdbc.queryForList("SELECT issuer_name FROM instrument WHERE id = ?", String.class,
                    client.instrumentId()).stream().findFirst().orElse(client.instrumentId());
            List<SourceLink> sources = new java.util.ArrayList<>();
            for (JsonNode id : mapper.readTree(row.sourceIdsJson())) {
                jdbc.query("SELECT id, title, canonical_url, source_domain FROM source_result WHERE id = ?",
                        (rs, n) -> new SourceLink(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                        UUID.fromString(id.asText())).forEach(sources::add);
            }
            return new DeliveryPackage(reportId, alertId, row.clientRef(), client.instrumentId(), row.status(),
                    mapper.readTree(row.reportJson()), sources, ReportText.portfolioLine(issuer), ReportText.DISCLAIMER);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("stored report unreadable", unreadable);
        }
    }

    /** Marks the recipient as being sent; refused if already sent or being sent by another request. */
    @Transactional
    public void claim(UUID reportId, UUID alertId, String agent) {
        Row row = row(reportId, alertId, agent);
        events.detail(row.eventId(), agent);
        Instant now = clock.instant();
        int claimed = jdbc.update("""
                UPDATE event_report_recipient SET status = 'SENDING', claimed_at = ?
                 WHERE report_id = ? AND alert_id = ? AND agent_subject = ?
                   AND (status = 'APPROVED' OR (status = 'SENDING' AND claimed_at < ?))
                """, Timestamp.from(now), reportId, alertId, agent, Timestamp.from(now.minus(STALE_CLAIM)));
        if (claimed == 0) throw new AlertMutationConflictException("This report was already sent or is being sent");
    }

    @Transactional
    public void complete(UUID reportId, UUID alertId, String agent, String messageId) {
        Instant now = clock.instant();
        int done = jdbc.update("""
                UPDATE event_report_recipient SET status = 'SENT', sent_at = ?, message_id = ?
                 WHERE report_id = ? AND alert_id = ? AND agent_subject = ? AND status = 'SENDING'
                """, Timestamp.from(now), messageId, reportId, alertId, agent);
        if (done == 0) throw new AlertNotFoundException();
        audit("alert.report-sent", reportId + "|" + alertId, Map.of("reportId", reportId, "alertId", alertId,
                "sentBy", agent, "messageId", messageId), now);
    }

    /** The send failed: the recipient can be tried again. */
    @Transactional
    public void release(UUID reportId, UUID alertId, String agent) {
        jdbc.update("""
                UPDATE event_report_recipient SET status = 'APPROVED', claimed_at = NULL
                 WHERE report_id = ? AND alert_id = ? AND agent_subject = ? AND status = 'SENDING'
                """, reportId, alertId, agent);
    }

    @Transactional(readOnly = true)
    public List<Recipient> recipients(UUID reportId, String agent) {
        return jdbc.query("SELECT alert_id, status FROM event_report_recipient WHERE report_id = ? AND agent_subject = ? ORDER BY alert_id",
                (rs, n) -> new Recipient(rs.getObject(1, UUID.class), rs.getString(2)), reportId, agent);
    }

    private record Row(UUID eventId, String clientRef, String status, String reportJson, String sourceIdsJson) {
    }

    private Row row(UUID reportId, UUID alertId, String agent) {
        List<Row> rows = jdbc.query("""
                SELECT r.public_event_id, p.client_ref, p.status, r.report::text, r.source_ids::text
                  FROM event_report_recipient p JOIN event_report r ON r.id = p.report_id
                 WHERE p.report_id = ? AND p.alert_id = ? AND p.agent_subject = ?
                """, (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                reportId, alertId, agent);
        if (rows.isEmpty()) throw new AlertNotFoundException();
        return rows.getFirst();
    }

    private void audit(String type, String key, Map<String, Object> payload, Instant now) {
        try {
            auditOutbox.saveAndFlush(new AuditOutbox(
                    UUID.nameUUIDFromBytes((type + "|" + key).getBytes(StandardCharsets.UTF_8)),
                    type, 1, mapper.writeValueAsString(payload), now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
