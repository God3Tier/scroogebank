package bank.alert.event;

import bank.alert.api.EvidenceResponse;
import bank.alert.crm.CurrentOwnership;
import bank.alert.scoring.MaterialityScorer;
import bank.alert.domain.AlertDecisionType;
import bank.alert.domain.AlertStatus;
import bank.alert.event.AgentEventModels.ActionResult;
import bank.alert.event.AgentEventModels.AffectedClient;
import bank.alert.event.AgentEventModels.EventCard;
import bank.alert.event.AgentEventModels.EventDetail;
import bank.alert.event.AgentEventModels.EventPage;
import bank.alert.event.AgentEventModels.EventSeverity;
import bank.alert.event.AgentEventModels.MemberOutcome;
import bank.alert.event.AgentEventModels.MemberResult;
import bank.alert.event.AgentEventModels.ReportSource;
import bank.alert.event.AgentEventModels.ReportView;
import bank.alert.report.ReportText;
import bank.alert.service.AlertNotFoundException;
import bank.alert.service.AlertQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Groups the per-client alerts of one authenticated agent into one card per public
 * event. Visibility follows the client's <em>current</em> agent, so another agent's
 * clients never appear, and a reassigned client moves to its new agent's card.
 */
@Service
public class AgentEventQueryService {

    private static final String OWNER = CurrentOwnership.OWNER_EXPRESSION;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final JdbcTemplate jdbc;
    private final CurrentOwnership ownership;

    public AgentEventQueryService(JdbcTemplate jdbc, CurrentOwnership ownership) {
        this.jdbc = jdbc;
        this.ownership = ownership;
    }

    @Transactional
    public EventPage list(String agentSubject, boolean onlyOpen, int limit) {
        return list(agentSubject, onlyOpen, true, null, limit);
    }

    @Transactional
    public EventPage list(String agentSubject, boolean onlyOpen, String encodedCursor, int limit) {
        return list(agentSubject, onlyOpen, true, encodedCursor, limit);
    }

    /**
     * @param onlyOpen      only events with a client still New
     * @param includeClosed also events whose clients are all Sent Out or Dismissed, which the main view leaves out
     */
    @Transactional
    public EventPage list(String agentSubject, boolean onlyOpen, boolean includeClosed, String encodedCursor, int limit) {
        ownership.refresh();
        AlertQueryService.Cursor cursor = AlertQueryService.decodeCursor(encodedCursor);
        StringBuilder sql = new StringBuilder("""
                SELECT pe.id AS event_id, sr.title, sr.canonical_url, sr.source_domain,
                       sr.published_at, sr.retrieved_at,
                       COUNT(DISTINCT a.client_ref) AS client_count,
                       COUNT(DISTINCT a.client_ref) FILTER (WHERE a.status = 'NEW') AS open_count,
                       MAX(a.score) AS top_score, MAX(a.created_at) AS latest,
                       (SELECT b.summary FROM briefing b JOIN alert a2 ON a2.id = b.alert_id
                         WHERE a2.public_event_id = pe.id ORDER BY b.created_at, b.id LIMIT 1) AS summary,
                       (SELECT b.ai_generated FROM briefing b JOIN alert a2 ON a2.id = b.alert_id
                         WHERE a2.public_event_id = pe.id ORDER BY b.created_at, b.id LIMIT 1) AS ai_generated,
                       (SELECT b.uncertainty FROM briefing b JOIN alert a2 ON a2.id = b.alert_id
                         WHERE a2.public_event_id = pe.id ORDER BY b.created_at, b.id LIMIT 1) AS uncertainty,
                       (SELECT r.report::text FROM event_report r
                         WHERE r.public_event_id = pe.id ORDER BY r.version DESC LIMIT 1) AS report_json,
                       (SELECT max(r.version) FROM event_report r WHERE r.public_event_id = pe.id) AS report_version,
                       (SELECT t.last_reason FROM event_report_attempt t WHERE t.public_event_id = pe.id) AS report_failure,
                       (SELECT count(*) FROM public_event_source s WHERE s.public_event_id = pe.id) AS source_count,
                       COALESCE(pe.instrument_id, (SELECT min(i.instrument_id) FROM public_event_instrument i
                                                    WHERE i.public_event_id = pe.id)) AS instrument_id,
                       (SELECT ins.ticker FROM instrument ins WHERE ins.id = COALESCE(pe.instrument_id,
                            (SELECT min(i.instrument_id) FROM public_event_instrument i WHERE i.public_event_id = pe.id))) AS ticker,
                       (SELECT ins.issuer_name FROM instrument ins WHERE ins.id = COALESCE(pe.instrument_id,
                            (SELECT min(i.instrument_id) FROM public_event_instrument i WHERE i.public_event_id = pe.id))) AS issuer
                  FROM alert a
                  JOIN public_event pe ON pe.id = a.public_event_id
                  JOIN source_result sr ON sr.id = pe.source_result_id
                  LEFT JOIN client_assignment ca ON ca.client_ref = a.client_ref
                 WHERE (CAST(? AS text) IS NULL OR %s = CAST(? AS text))
                   AND NOT (NOT EXISTS (SELECT 1 FROM event_report r0 WHERE r0.public_event_id = pe.id)
                            AND COALESCE((SELECT t0.attempts FROM event_report_attempt t0 WHERE t0.public_event_id = pe.id), 0) >= ?)
                 GROUP BY pe.id, sr.title, sr.canonical_url, sr.source_domain, sr.published_at, sr.retrieved_at
                """.formatted(OWNER));
        List<Object> args = new ArrayList<>();
        args.add(agentSubject);
        args.add(agentSubject); // a null agent (an administrator) matches every owner
        // An event whose report the model could not write and that has no fallback either is left out: nothing is shown for it.
        args.add(bank.alert.report.SafeClientReportGenerator.GIVE_UP_AFTER_FAILED_DRAFTS);
        List<String> having = new ArrayList<>();
        if (onlyOpen) having.add("COUNT(*) FILTER (WHERE a.status = 'NEW') > 0");
        if (!includeClosed) having.add("COUNT(*) FILTER (WHERE a.status IN ('NEW', 'INFORMED')) > 0");
        if (!having.isEmpty()) sql.append(" HAVING ").append(String.join(" AND ", having));
        if (cursor != null) {
            sql.append(having.isEmpty() ? " HAVING " : " AND ");
            sql.append("(MAX(a.created_at) < ? OR (MAX(a.created_at) = ? AND pe.id < ?))");
            args.add(Timestamp.from(cursor.createdAt()));
            args.add(Timestamp.from(cursor.createdAt()));
            args.add(cursor.id());
        }
        sql.append(" ORDER BY MAX(a.created_at) DESC, pe.id DESC LIMIT ?");
        args.add(limit + 1);
        List<EventCard> rows = new ArrayList<>(jdbc.query(sql.toString(), (rs, n) -> card(rs), args.toArray()));
        String nextCursor = null;
        if (rows.size() > limit) {
            rows.remove(rows.size() - 1);
            EventCard last = rows.getLast();
            nextCursor = AlertQueryService.encodeCursor(last.latestAlertAt(), last.eventId());
        }
        return new EventPage(rows, nextCursor);
    }

    @Transactional
    public EventDetail detail(UUID eventId, String agentSubject) {
        ownership.refresh();
        List<EventCard> cards = jdbc.query("""
                SELECT pe.id AS event_id, sr.title, sr.canonical_url, sr.source_domain,
                       sr.published_at, sr.retrieved_at,
                       COUNT(DISTINCT a.client_ref) AS client_count,
                       COUNT(DISTINCT a.client_ref) FILTER (WHERE a.status = 'NEW') AS open_count,
                       MAX(a.score) AS top_score, MAX(a.created_at) AS latest,
                       (SELECT b.summary FROM briefing b JOIN alert a2 ON a2.id = b.alert_id
                         WHERE a2.public_event_id = pe.id ORDER BY b.created_at, b.id LIMIT 1) AS summary,
                       (SELECT b.ai_generated FROM briefing b JOIN alert a2 ON a2.id = b.alert_id
                         WHERE a2.public_event_id = pe.id ORDER BY b.created_at, b.id LIMIT 1) AS ai_generated,
                       (SELECT b.uncertainty FROM briefing b JOIN alert a2 ON a2.id = b.alert_id
                         WHERE a2.public_event_id = pe.id ORDER BY b.created_at, b.id LIMIT 1) AS uncertainty,
                       (SELECT r.report::text FROM event_report r
                         WHERE r.public_event_id = pe.id ORDER BY r.version DESC LIMIT 1) AS report_json,
                       (SELECT max(r.version) FROM event_report r WHERE r.public_event_id = pe.id) AS report_version,
                       (SELECT t.last_reason FROM event_report_attempt t WHERE t.public_event_id = pe.id) AS report_failure,
                       (SELECT count(*) FROM public_event_source s WHERE s.public_event_id = pe.id) AS source_count,
                       COALESCE(pe.instrument_id, (SELECT min(i.instrument_id) FROM public_event_instrument i
                                                    WHERE i.public_event_id = pe.id)) AS instrument_id,
                       (SELECT ins.ticker FROM instrument ins WHERE ins.id = COALESCE(pe.instrument_id,
                            (SELECT min(i.instrument_id) FROM public_event_instrument i WHERE i.public_event_id = pe.id))) AS ticker,
                       (SELECT ins.issuer_name FROM instrument ins WHERE ins.id = COALESCE(pe.instrument_id,
                            (SELECT min(i.instrument_id) FROM public_event_instrument i WHERE i.public_event_id = pe.id))) AS issuer
                  FROM alert a
                  JOIN public_event pe ON pe.id = a.public_event_id
                  JOIN source_result sr ON sr.id = pe.source_result_id
                  LEFT JOIN client_assignment ca ON ca.client_ref = a.client_ref
                 WHERE pe.id = ? AND (CAST(? AS text) IS NULL OR %s = CAST(? AS text))
                 GROUP BY pe.id, sr.title, sr.canonical_url, sr.source_domain, sr.published_at, sr.retrieved_at
                """.formatted(OWNER), (rs, n) -> card(rs), eventId, agentSubject, agentSubject);
        if (cards.isEmpty()) throw new AlertNotFoundException();

        List<AffectedClient> clients = jdbc.query("""
                SELECT a.id, a.client_ref, a.instrument_id, a.status, a.score, a.version,
                       hs.value_sgd, hs.account_ref,
                       ROUND(hs.value_sgd * 100 / NULLIF((
                           SELECT SUM(h2.value_sgd) FROM holding_snapshot h2
                            WHERE (hs.portfolio_snapshot_id IS NOT NULL
                                   AND h2.portfolio_snapshot_id = hs.portfolio_snapshot_id)
                               OR (hs.portfolio_snapshot_id IS NULL AND h2.portfolio_snapshot_id IS NULL
                                   AND h2.client_ref = a.client_ref
                                   AND h2.source_version = hs.source_version
                                   AND h2.valued_at = hs.valued_at)), 0), 4) AS pct
                  FROM alert a
                  JOIN holding_snapshot hs ON hs.id = a.holding_snapshot_id
                  LEFT JOIN client_assignment ca ON ca.client_ref = a.client_ref
                 WHERE a.public_event_id = ? AND (CAST(? AS text) IS NULL OR %s = CAST(? AS text))
                 ORDER BY a.score DESC, a.client_ref, a.instrument_id
                """.formatted(OWNER), (rs, n) -> new AffectedClient(
                rs.getObject("id", UUID.class), rs.getString("client_ref"), rs.getString("account_ref"),
                rs.getString("instrument_id"),
                AlertStatus.valueOf(rs.getString("status")), rs.getBigDecimal("score"),
                rs.getBigDecimal("pct"),
                rs.getBigDecimal("value_sgd").setScale(2, RoundingMode.HALF_UP).toPlainString(),
                rs.getLong("version")), eventId, agentSubject, agentSubject);

        List<UUID> actions = jdbc.queryForList("""
                SELECT id FROM agent_event_action WHERE public_event_id = ? AND (CAST(? AS text) IS NULL OR actor_subject = CAST(? AS text))
                 ORDER BY created_at DESC, id DESC LIMIT 1
                """, UUID.class, eventId, agentSubject, agentSubject);
        ActionResult latest = actions.isEmpty() ? null : loadAction(actions.getFirst());
        return new EventDetail(cards.getFirst(), clients, latest, loadReport(eventId, clients), loadSeverity(eventId, agentSubject));
    }

    /** The severity part of the score. It is the same for every client of the event, so any of this agent's alerts gives it. */
    private EventSeverity loadSeverity(UUID eventId, String agentSubject) {
        List<EventSeverity> found = jdbc.query("""
                SELECT f.contribution, pe.classification, pe.direction
                  FROM alert a
                  JOIN alert_factor f ON f.alert_id = a.id AND f.code = 'EVENT_SEVERITY'
                  JOIN public_event pe ON pe.id = a.public_event_id
                  LEFT JOIN client_assignment ca ON ca.client_ref = a.client_ref
                 WHERE a.public_event_id = ? AND (CAST(? AS text) IS NULL OR %s = CAST(? AS text))
                 ORDER BY f.contribution DESC LIMIT 1
                """.formatted(OWNER), (rs, n) -> new EventSeverity(rs.getBigDecimal("contribution"),
                MaterialityScorer.SEVERITY_WEIGHT.intValue(), rs.getString("classification"), rs.getString("direction")),
                eventId, agentSubject, agentSubject);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** The latest report with every source linked to the event, or null before one has been written. */
    private ReportView loadReport(UUID eventId, List<AffectedClient> clients) {
        List<Object[]> rows = jdbc.query("""
                SELECT r.id, r.version, r.schema_version, r.report::text, r.source_ids::text, r.created_at,
                       (SELECT count(*) FROM event_report r2 WHERE r2.public_event_id = r.public_event_id)
                  FROM event_report r WHERE r.public_event_id = ? ORDER BY r.version DESC LIMIT 1
                """, (rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getInt(7)}, eventId);
        if (rows.isEmpty()) return null;
        Object[] row = rows.getFirst();
        try {
            java.util.Set<String> used = new java.util.HashSet<>();
            MAPPER.readTree((String) row[4]).forEach(id -> used.add(id.asText()));
            List<ReportSource> sources = jdbc.query("""
                    SELECT sr.id, sr.title, sr.canonical_url, sr.source_domain, sr.published_at, sr.retrieved_at
                      FROM public_event_source s JOIN source_result sr ON sr.id = s.source_result_id
                     WHERE s.public_event_id = ?
                     ORDER BY sr.published_at NULLS LAST, sr.canonical_url
                    """, (rs, n) -> new ReportSource(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                    rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                    used.contains(rs.getString(1))), eventId);
            java.util.Map<String, String> lines = new java.util.LinkedHashMap<>();
            for (String instrumentId : clients.stream().map(AffectedClient::instrumentId).distinct().sorted().toList()) {
                List<String> issuer = jdbc.queryForList("SELECT issuer_name FROM instrument WHERE id = ?", String.class, instrumentId);
                if (!issuer.isEmpty()) lines.put(instrumentId, ReportText.portfolioLine(issuer.getFirst()));
            }
            return new ReportView((UUID) row[0], (Integer) row[1], (Integer) row[6], (String) row[2],
                    MAPPER.readTree((String) row[3]), sources, lines, ReportText.DISCLAIMER, (java.time.Instant) row[5]);
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            return null;
        }
    }

    ActionResult loadAction(UUID actionId) {
        return jdbc.query("""
                SELECT id, public_event_id, decision, created_at FROM agent_event_action WHERE id = ?
                """, rs -> {
            if (!rs.next()) return null;
            List<MemberResult> members = jdbc.query("""
                    SELECT alert_id, outcome, previous_status, new_status FROM agent_event_action_member
                     WHERE action_id = ? ORDER BY alert_id
                    """, (m, n) -> new MemberResult(m.getObject("alert_id", UUID.class),
                    MemberOutcome.valueOf(m.getString("outcome")),
                    m.getString("previous_status") == null ? null : AlertStatus.valueOf(m.getString("previous_status")),
                    m.getString("new_status") == null ? null : AlertStatus.valueOf(m.getString("new_status"))),
                    actionId);
            return new ActionResult(rs.getObject("id", UUID.class), rs.getObject("public_event_id", UUID.class),
                    AlertDecisionType.valueOf(rs.getString("decision")),
                    rs.getTimestamp("created_at").toInstant(), members);
        }, actionId);
    }

    private static EventCard card(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp published = rs.getTimestamp("published_at");
        BigDecimal top = rs.getBigDecimal("top_score");
        String headline = rs.getString("title");
        String summary = rs.getString("summary");
        String uncertainty = rs.getString("uncertainty");
        boolean aiGenerated = rs.getBoolean("ai_generated");
        String reportJson = rs.getString("report_json");
        if (reportJson != null) {
            // The event's report, once there is one, is what the card says (ADR-0084).
            try {
                com.fasterxml.jackson.databind.JsonNode report = MAPPER.readTree(reportJson);
                if (report.hasNonNull("headline")) headline = report.get("headline").asText();
                StringBuilder text = new StringBuilder();
                report.path("executiveSummary").forEach(sentence -> {
                    if (!text.isEmpty()) text.append(' ');
                    text.append(sentence.path("text").asText());
                });
                if (!text.isEmpty()) summary = text.toString();
                if (report.hasNonNull("uncertainty")) uncertainty = report.get("uncertainty").asText();
                aiGenerated = true;
            } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
                // Keep the first briefing's wording.
            }
        }
        return new EventCard(rs.getObject("event_id", UUID.class), headline,
                summary, aiGenerated, uncertainty,
                new EvidenceResponse(rs.getString("title"), rs.getString("canonical_url"),
                        rs.getString("source_domain"), published == null ? null : published.toInstant(),
                        rs.getTimestamp("retrieved_at").toInstant()),
                rs.getInt("client_count"), rs.getInt("open_count"), top,
                rs.getTimestamp("latest").toInstant(),
                rs.getString("instrument_id"), rs.getString("ticker"), rs.getString("issuer"),
                rs.getInt("source_count"), (Integer) rs.getObject("report_version"), rs.getString("report_failure"));
    }
}
