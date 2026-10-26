package bank.clienthealth.explanation;

import bank.clienthealth.audit.RelationshipHealthAuditEvent;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcExplanationRepository implements ExplanationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcExplanationRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc; this.transactions = transactions; this.json = json;
    }

    @Override
    public Optional<Record> find(String taskId, String findingId, long version, String promptVersion) {
        return query("task_id = ? AND finding_id = ? AND task_version = ? AND prompt_template_version = ?",
                taskId, findingId, version, promptVersion);
    }

    @Override
    public Optional<Record> findForTaskVersion(String taskId, long version) {
        return query("task_id = ? AND task_version = ? ORDER BY generated_at DESC LIMIT 1", taskId, version);
    }

    private Optional<Record> query(String predicate, Object... arguments) {
        return jdbc.query("""
                SELECT explanation_id, task_id, finding_id, task_version, summary,
                       suggested_review_step, disclaimer, evidence_codes, source,
                       prompt_template_version, model_id, generated_at
                FROM relationship_task_explanations WHERE %s
                """.formatted(predicate), this::row, arguments).stream().findFirst();
    }

    @Override
    public Record saveIfAbsent(Record value, RelationshipHealthAuditEvent audit) {
        return transactions.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO relationship_task_explanations
                        (explanation_id, task_id, finding_id, task_version, summary,
                         suggested_review_step, disclaimer, evidence_codes, source,
                         prompt_template_version, model_id, generated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?)
                    ON CONFLICT (task_id, finding_id, task_version, prompt_template_version) DO NOTHING
                    """, value.explanationId(), value.taskId(), value.findingId(), value.taskVersion(),
                    value.summary(), value.suggestedReviewStep(), value.disclaimer(),
                    value.evidenceCodes().stream().map(Enum::name).toArray(String[]::new),
                    value.source().name(), value.promptTemplateVersion(), value.modelId(),
                    Timestamp.from(value.generatedAt()));
            if (inserted == 1) enqueue(audit);
            return find(value.taskId(), value.findingId(), value.taskVersion(),
                    value.promptTemplateVersion()).orElseThrow();
        });
    }

    private void enqueue(RelationshipHealthAuditEvent event) {
        jdbc.update("""
                INSERT INTO relationship_health_audit_outbox
                    (event_id, event_type, action, source_service, actor_id, payload,
                     created_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, event.eventId(), RelationshipHealthAuditEvent.EVENT_TYPE, event.action().name(),
                RelationshipHealthAuditEvent.SOURCE_SERVICE, event.actorId(), write(event.payload()),
                Timestamp.from(event.payload().occurredAt()), Timestamp.from(event.payload().occurredAt()));
    }

    private Record row(ResultSet rs, int ignored) throws SQLException {
        String[] codes = (String[]) rs.getArray("evidence_codes").getArray();
        return new Record(rs.getString("explanation_id"), rs.getString("task_id"),
                rs.getString("finding_id"), rs.getLong("task_version"), rs.getString("summary"),
                rs.getString("suggested_review_step"), rs.getString("disclaimer"),
                Arrays.stream(codes).map(SignalType::valueOf).toList(),
                ExplanationResult.Source.valueOf(rs.getString("source")),
                rs.getString("prompt_template_version"), rs.getString("model_id"),
                rs.getObject("generated_at", OffsetDateTime.class).toInstant());
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException("Cannot serialize audit", impossible); }
    }
}
