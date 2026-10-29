package bank.alert.repository;

import bank.alert.api.BriefingResponse;
import bank.alert.crm.CurrentOwnership;
import bank.alert.api.EvidenceResponse;
import bank.alert.api.ExplanationFactorResponse;
import bank.alert.api.AlertSummaryResponse;
import bank.alert.domain.AlertStatus;
import bank.alert.domain.ValuationFreshness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AlertListRepository {

    private static final String SELECT = """
            SELECT a.id, a.client_ref, a.instrument_id, sr.title, a.status,
                   a.score, a.created_at, a.version
              FROM alert a
              JOIN public_event pe ON pe.id = a.public_event_id
              JOIN source_result sr ON sr.id = pe.source_result_id
              LEFT JOIN client_assignment ca ON ca.client_ref = a.client_ref
             WHERE 1 = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public AlertListRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AlertSummaryResponse> findVisible(
            String actorSubject,
            boolean admin,
            AlertStatus status,
            BigDecimal minimumScore,
            Instant cursorCreatedAt,
            UUID cursorId,
            int fetchSize) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> arguments = new ArrayList<>();
        if (!admin) {
            sql.append(" AND " + CurrentOwnership.OWNER_EXPRESSION + " = ?");
            arguments.add(actorSubject);
        }
        if (status != null) {
            sql.append(" AND a.status = ?");
            arguments.add(status.name());
        }
        if (minimumScore != null) {
            sql.append(" AND a.score >= ?");
            arguments.add(minimumScore);
        }
        if (cursorCreatedAt != null) {
            sql.append(" AND (a.created_at < ? OR (a.created_at = ? AND a.id < ?))");
            Timestamp timestamp = Timestamp.from(cursorCreatedAt);
            arguments.add(timestamp);
            arguments.add(timestamp);
            arguments.add(cursorId);
        }
        sql.append(" ORDER BY a.created_at DESC, a.id DESC LIMIT ?");
        arguments.add(fetchSize);

        return jdbcTemplate.query(sql.toString(), (resultSet, rowNumber) ->
                new AlertSummaryResponse(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("client_ref"),
                        resultSet.getString("instrument_id"),
                        resultSet.getString("title"),
                        AlertStatus.valueOf(resultSet.getString("status")),
                        resultSet.getBigDecimal("score"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getLong("version")), arguments.toArray());
    }

    public Optional<DetailRow> findVisibleDetail(UUID alertId, String actorSubject, boolean admin) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.client_ref, a.instrument_id, sr.title, a.status,
                       a.score, a.created_at, a.version,
                       hs.value_sgd, hs.valued_at, hs.freshness,
                       ROUND(hs.value_sgd * 100 / NULLIF((
                           SELECT SUM(h2.value_sgd)
                             FROM holding_snapshot h2
                            WHERE (hs.portfolio_snapshot_id IS NOT NULL
                                   AND h2.portfolio_snapshot_id = hs.portfolio_snapshot_id)
                               OR (hs.portfolio_snapshot_id IS NULL
                                   AND h2.portfolio_snapshot_id IS NULL
                                   AND h2.client_ref = a.client_ref
                                   AND h2.source_version = hs.source_version
                                   AND h2.valued_at = hs.valued_at)
                       ), 0), 4) AS portfolio_percentage,
                       sr.canonical_url, sr.source_domain, sr.published_at, sr.retrieved_at,
                       b.summary, b.ai_generated, b.model_version, b.prompt_version, b.uncertainty
                  FROM alert a
                  LEFT JOIN client_assignment ca ON ca.client_ref = a.client_ref
                  JOIN holding_snapshot hs ON hs.id = a.holding_snapshot_id
                  JOIN public_event pe ON pe.id = a.public_event_id
                  JOIN source_result sr ON sr.id = pe.source_result_id
                  LEFT JOIN briefing b ON b.alert_id = a.id
                 WHERE a.id = ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(alertId);
        if (!admin) {
            sql.append(" AND " + CurrentOwnership.OWNER_EXPRESSION + " = ?");
            arguments.add(actorSubject);
        }

        List<DetailRow> results = jdbcTemplate.query(sql.toString(), (resultSet, rowNumber) -> {
            Timestamp publishedAt = resultSet.getTimestamp("published_at");
            BriefingResponse briefing = resultSet.getString("summary") == null
                    ? null
                    : new BriefingResponse(
                            resultSet.getString("summary"),
                            resultSet.getBoolean("ai_generated"),
                            resultSet.getString("model_version"),
                            resultSet.getString("prompt_version"),
                            resultSet.getString("uncertainty"));
            return new DetailRow(
                    new AlertSummaryResponse(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("client_ref"),
                            resultSet.getString("instrument_id"),
                            resultSet.getString("title"),
                            AlertStatus.valueOf(resultSet.getString("status")),
                            resultSet.getBigDecimal("score"),
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getLong("version")),
                    resultSet.getBigDecimal("value_sgd"),
                    resultSet.getBigDecimal("portfolio_percentage"),
                    resultSet.getTimestamp("valued_at").toInstant(),
                    ValuationFreshness.valueOf(resultSet.getString("freshness")),
                    new EvidenceResponse(
                            resultSet.getString("title"),
                            resultSet.getString("canonical_url"),
                            resultSet.getString("source_domain"),
                            publishedAt == null ? null : publishedAt.toInstant(),
                            resultSet.getTimestamp("retrieved_at").toInstant()),
                    briefing);
        }, arguments.toArray());
        return results.stream().findFirst();
    }

    public List<ExplanationFactorResponse> findFactors(UUID alertId) {
        return jdbcTemplate.query("""
                        SELECT code, contribution, description
                          FROM alert_factor
                         WHERE alert_id = ?
                         ORDER BY sequence_number
                        """,
                (resultSet, rowNumber) -> new ExplanationFactorResponse(
                        resultSet.getString("code"),
                        resultSet.getBigDecimal("contribution"),
                        resultSet.getString("description")),
                alertId);
    }

    public record DetailRow(
            AlertSummaryResponse summary,
            BigDecimal amountSgd,
            BigDecimal portfolioPercentage,
            Instant valuationAsOf,
            ValuationFreshness freshness,
            EvidenceResponse evidence,
            BriefingResponse briefing) {
    }
}
