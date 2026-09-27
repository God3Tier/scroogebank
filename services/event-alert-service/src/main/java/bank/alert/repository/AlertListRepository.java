package bank.alert.repository;

import bank.alert.api.AlertSummaryResponse;
import bank.alert.domain.AlertStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class AlertListRepository {

    private static final String SELECT = """
            SELECT a.id, a.client_ref, a.instrument_id, sr.title, a.status,
                   a.score, a.created_at, a.version
              FROM alert a
              JOIN public_event pe ON pe.id = a.public_event_id
              JOIN source_result sr ON sr.id = pe.source_result_id
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
            sql.append(" AND a.assigned_agent_subject = ?");
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
}
