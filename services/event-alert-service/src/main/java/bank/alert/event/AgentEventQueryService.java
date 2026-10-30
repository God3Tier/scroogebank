package bank.alert.event;

import bank.alert.api.EvidenceResponse;
import bank.alert.crm.CurrentOwnership;
import bank.alert.domain.AlertDecisionType;
import bank.alert.domain.AlertStatus;
import bank.alert.event.AgentEventModels.ActionResult;
import bank.alert.event.AgentEventModels.AffectedClient;
import bank.alert.event.AgentEventModels.EventCard;
import bank.alert.event.AgentEventModels.EventDetail;
import bank.alert.event.AgentEventModels.EventPage;
import bank.alert.event.AgentEventModels.MemberOutcome;
import bank.alert.event.AgentEventModels.MemberResult;
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

    private final JdbcTemplate jdbc;
    private final CurrentOwnership ownership;

    public AgentEventQueryService(JdbcTemplate jdbc, CurrentOwnership ownership) {
        this.jdbc = jdbc;
        this.ownership = ownership;
    }

    @Transactional
    public EventPage list(String agentSubject, boolean onlyOpen, int limit) {
        return list(agentSubject, onlyOpen, null, limit);
    }

    @Transactional
    public EventPage list(String agentSubject, boolean onlyOpen, String encodedCursor, int limit) {
        ownership.refresh();
        AlertQueryService.Cursor cursor = AlertQueryService.decodeCursor(encodedCursor);
        StringBuilder sql = new StringBuilder("""
                SELECT pe.id AS event_id, sr.title, sr.canonical_url, sr.source_domain,
                       sr.published_at, sr.retrieved_at,
                       COUNT(DISTINCT a.client_ref) AS client_count,
                       COUNT(DISTINCT a.client_ref) FILTER (WHERE a.status IN ('NEW', 'MONITORING')) AS open_count,
                       MAX(a.score) AS top_score, MAX(a.created_at) AS latest,
                       (SELECT b.summary FROM briefing b JOIN alert a2 ON a2.id = b.alert_id
                         WHERE a2.public_event_id = pe.id ORDER BY b.created_at, b.id LIMIT 1) AS summary,
                       (SELECT b.ai_generated FROM briefing b JOIN alert a2 ON a2.id = b.alert_id
                         WHERE a2.public_event_id = pe.id ORDER BY b.created_at, b.id LIMIT 1) AS ai_generated,
                       (SELECT b.uncertainty FROM briefing b JOIN alert a2 ON a2.id = b.alert_id
                         WHERE a2.public_event_id = pe.id ORDER BY b.created_at, b.id LIMIT 1) AS uncertainty
                  FROM alert a
                  JOIN public_event pe ON pe.id = a.public_event_id
                  JOIN source_result sr ON sr.id = pe.source_result_id
                  LEFT JOIN client_assignment ca ON ca.client_ref = a.client_ref
                 WHERE %s = ?
                 GROUP BY pe.id, sr.title, sr.canonical_url, sr.source_domain, sr.published_at, sr.retrieved_at
                """.formatted(OWNER));
        List<Object> args = new ArrayList<>();
        args.add(agentSubject);
        if (onlyOpen) sql.append(" HAVING COUNT(*) FILTER (WHERE a.status IN ('NEW', 'MONITORING')) > 0");
        if (cursor != null) {
            sql.append(onlyOpen ? " AND " : " HAVING ");
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
                       COUNT(DISTINCT a.client_ref) FILTER (WHERE a.status IN ('NEW', 'MONITORING')) AS open_count,
                       MAX(a.score) AS top_score, MAX(a.created_at) AS latest,
                       (SELECT b.summary FROM briefing b JOIN alert a2 ON a2.id = b.alert_id
                         WHERE a2.public_event_id = pe.id ORDER BY b.created_at, b.id LIMIT 1) AS summary,
                       (SELECT b.ai_generated FROM briefing b JOIN alert a2 ON a2.id = b.alert_id
                         WHERE a2.public_event_id = pe.id ORDER BY b.created_at, b.id LIMIT 1) AS ai_generated,
                       (SELECT b.uncertainty FROM briefing b JOIN alert a2 ON a2.id = b.alert_id
                         WHERE a2.public_event_id = pe.id ORDER BY b.created_at, b.id LIMIT 1) AS uncertainty
                  FROM alert a
                  JOIN public_event pe ON pe.id = a.public_event_id
                  JOIN source_result sr ON sr.id = pe.source_result_id
                  LEFT JOIN client_assignment ca ON ca.client_ref = a.client_ref
                 WHERE pe.id = ? AND %s = ?
                 GROUP BY pe.id, sr.title, sr.canonical_url, sr.source_domain, sr.published_at, sr.retrieved_at
                """.formatted(OWNER), (rs, n) -> card(rs), eventId, agentSubject);
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
                 WHERE a.public_event_id = ? AND %s = ?
                 ORDER BY a.score DESC, a.client_ref, a.instrument_id
                """.formatted(OWNER), (rs, n) -> new AffectedClient(
                rs.getObject("id", UUID.class), rs.getString("client_ref"), rs.getString("account_ref"),
                rs.getString("instrument_id"),
                AlertStatus.valueOf(rs.getString("status")), rs.getBigDecimal("score"),
                rs.getBigDecimal("pct"),
                rs.getBigDecimal("value_sgd").setScale(2, RoundingMode.HALF_UP).toPlainString(),
                rs.getLong("version")), eventId, agentSubject);

        List<UUID> actions = jdbc.queryForList("""
                SELECT id FROM agent_event_action WHERE public_event_id = ? AND actor_subject = ?
                 ORDER BY created_at DESC, id DESC LIMIT 1
                """, UUID.class, eventId, agentSubject);
        ActionResult latest = actions.isEmpty() ? null : loadAction(actions.getFirst());
        return new EventDetail(cards.getFirst(), clients, latest);
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
        return new EventCard(rs.getObject("event_id", UUID.class), rs.getString("title"),
                rs.getString("summary"), rs.getBoolean("ai_generated"), rs.getString("uncertainty"),
                new EvidenceResponse(rs.getString("title"), rs.getString("canonical_url"),
                        rs.getString("source_domain"), published == null ? null : published.toInstant(),
                        rs.getTimestamp("retrieved_at").toInstant()),
                rs.getInt("client_count"), rs.getInt("open_count"), top,
                rs.getTimestamp("latest").toInstant());
    }
}
