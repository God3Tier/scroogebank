package bank.clienthealth.task.persistence;

import bank.clienthealth.audit.RelationshipHealthAuditEvent;
import bank.clienthealth.audit.AuditOutboxRepository;
import bank.clienthealth.audit.JdbcAuditOutboxRepository;
import bank.clienthealth.metrics.ClientHealthMetrics;
import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.ClientHealthFinding.AnalysisWindow;
import bank.clienthealth.contract.ClientHealthFinding.EvidenceDetails;
import bank.clienthealth.contract.ClientHealthFinding.PriorityInputs;
import bank.clienthealth.contract.ClientHealthFinding.SignalEvidence;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.TaskUpsertResult;
import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.domain.RelationshipTask.Action;
import bank.clienthealth.task.domain.RelationshipTask.AssignmentSnapshot;
import bank.clienthealth.task.domain.RelationshipTask.Evidence;
import bank.clienthealth.task.domain.RelationshipTask.HistoryEntry;
import bank.clienthealth.task.domain.RelationshipTask.Status;
import bank.clienthealth.task.port.RelationshipTaskQueryPort;
import bank.clienthealth.task.port.TaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL adapter for atomic task writes and ownership-scoped API reads. */
public final class JdbcRelationshipTaskRepository implements TaskRepository, RelationshipTaskQueryPort {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final AuditOutboxRepository auditOutbox;

    public JdbcRelationshipTaskRepository(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json
    ) {
        this(jdbc, transactions, json, new JdbcAuditOutboxRepository(
                jdbc, transactions, json, () -> UUID.randomUUID().toString(), ClientHealthMetrics.noOp()));
    }

    public JdbcRelationshipTaskRepository(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json,
            AuditOutboxRepository auditOutbox
    ) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.transactions = transactions;
        this.json = json;
        this.auditOutbox = auditOutbox;
    }

    @Override
    public <T> T inTransaction(Function<Session, T> work) {
        try {
            return transactions.execute(status -> work.apply(new JdbcSession()));
        } catch (DataIntegrityViolationException conflict) {
            throw new ConflictException("Task persistence constraint conflict");
        }
    }

    @Override
    public TaskPage findTasks(Viewer viewer, TaskFilter filter) {
        return transactions.execute(status -> findTasksInTransaction(viewer, filter));
    }

    private TaskPage findTasksInTransaction(Viewer viewer, TaskFilter filter) {
        CursorBoundary cursor = decodeCursor(filter.cursor());
        StringBuilder sql = new StringBuilder("""
                SELECT t.task_id, t.updated_at
                FROM relationship_tasks t
                WHERE 1 = 1
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (!filter.statuses().isEmpty()) {
            sql.append(" AND t.status IN (:statuses)");
            parameters.addValue("statuses", filter.statuses().stream().map(Enum::name).toList());
        }
        if (!filter.severities().isEmpty()) {
            sql.append(" AND t.severity IN (:severities)");
            parameters.addValue("severities", filter.severities().stream().map(Enum::name).toList());
        }
        if (cursor != null) {
            sql.append(" AND (t.updated_at < :cursorUpdatedAt "
                    + "OR (t.updated_at = :cursorUpdatedAt AND t.task_id > :cursorTaskId))");
            parameters.addValue("cursorUpdatedAt", Timestamp.from(cursor.updatedAt()))
                    .addValue("cursorTaskId", cursor.taskId());
        }
        sql.append(" ORDER BY t.updated_at DESC, t.task_id ASC LIMIT :limit");
        parameters.addValue("limit", filter.limit() + 1);
        List<CursorBoundary> rows = namedJdbc.query(sql.toString(), parameters,
                (rs, row) -> new CursorBoundary(instant(rs, "updated_at"), rs.getString("task_id")));
        boolean more = rows.size() > filter.limit();
        if (more) rows = rows.subList(0, filter.limit());
        List<RelationshipTask> tasks = rows.stream().map(CursorBoundary::taskId).map(this::loadTask).toList();
        return new TaskPage(tasks, more ? encodeCursor(rows.getLast()) : null);
    }

    @Override
    public Optional<RelationshipTask> findTask(String taskId, Viewer viewer) {
        return transactions.execute(status -> findTaskInTransaction(taskId, viewer));
    }

    private Optional<RelationshipTask> findTaskInTransaction(String taskId, Viewer viewer) {
        List<String> ids = jdbc.query("SELECT task_id FROM relationship_tasks WHERE task_id = ?",
                (rs, row) -> rs.getString(1), taskId);
        return ids.stream().findFirst().map(this::loadTask);
    }

    private RelationshipTask loadTask(String taskId) {
        TaskRow task = jdbc.queryForObject("""
                SELECT task_id, client_id, status, severity, version, created_at, updated_at
                FROM relationship_tasks WHERE task_id = ? FOR SHARE
                """, (rs, row) -> new TaskRow(rs.getString("task_id"), rs.getString("client_id"),
                Status.valueOf(rs.getString("status")), Severity.valueOf(rs.getString("severity")),
                rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at")), taskId);
        AssignmentSnapshot assignment = jdbc.queryForObject("""
                SELECT client_id, agent_subject, assignment_active, assignment_version, assignment_updated_at
                FROM task_assignment_snapshots WHERE task_id = ?
                """, (rs, row) -> new AssignmentSnapshot(rs.getString("client_id"),
                rs.getString("agent_subject"), rs.getBoolean("assignment_active"),
                rs.getLong("assignment_version"), instant(rs, "assignment_updated_at")), taskId);
        List<Evidence> evidence = jdbc.query("""
                SELECT * FROM relationship_task_evidence WHERE task_id = ? ORDER BY evidence_id
                """, (rs, row) -> evidence(rs), taskId);
        List<HistoryEntry> history = jdbc.query("""
                SELECT * FROM relationship_task_history WHERE task_id = ? ORDER BY task_version
                """, (rs, row) -> history(rs), taskId);
        return new RelationshipTask(task.taskId(), task.clientId(), assignment, task.status(), task.severity(),
                task.version(), task.createdAt(), task.updatedAt(), evidence, history);
    }

    private final class JdbcSession implements Session {
        @Override
        public Optional<ProcessedFinding> findProcessedFinding(String findingId) {
            return jdbc.query("""
                    SELECT finding_payload::text, task_id, outcome, result_version, processed_at
                    FROM processed_client_health_findings WHERE finding_id = ?
                    """, (rs, row) -> new ProcessedFinding(
                    readFinding(rs.getString(1)),
                    new TaskUpsertResult(rs.getString(2), TaskUpsertResult.Outcome.valueOf(rs.getString(3)),
                            rs.getLong(4)), instant(rs, "processed_at")), findingId).stream().findFirst();
        }

        @Override public Optional<RelationshipTask> findActiveTask(String clientId) {
            return findOne("SELECT task_id FROM relationship_tasks WHERE client_id = ? "
                    + "AND status IN ('OPEN','IN_REVIEW')", clientId);
        }
        @Override public Optional<RelationshipTask> findLatestClosedTask(String clientId) {
            return findOne("SELECT task_id FROM relationship_tasks WHERE client_id = ? "
                    + "AND status IN ('COMPLETED','DISMISSED') ORDER BY updated_at DESC, task_id LIMIT 1", clientId);
        }
        @Override public Optional<RelationshipTask> findTask(String taskId) {
            return findOne("SELECT task_id FROM relationship_tasks WHERE task_id = ?", taskId);
        }
        private Optional<RelationshipTask> findOne(String sql, String value) {
            return jdbc.query(sql, (rs, row) -> rs.getString(1), value).stream().findFirst()
                    .map(JdbcRelationshipTaskRepository.this::loadTask);
        }

        @Override
        public Set<String> findFingerprints(String clientId, Collection<String> fingerprints) {
            if (fingerprints.isEmpty()) return Set.of();
            return Set.copyOf(namedJdbc.query("""
                    SELECT fingerprint FROM relationship_task_evidence
                    WHERE client_id = :clientId AND fingerprint IN (:fingerprints)
                    """, new MapSqlParameterSource("clientId", clientId).addValue("fingerprints", fingerprints),
                    (rs, row) -> rs.getString(1)));
        }

        @Override
        public Optional<RelationshipTask> findLatestTaskContainingAny(
                String clientId, Collection<String> fingerprints
        ) {
            if (fingerprints.isEmpty()) return Optional.empty();
            List<String> ids = namedJdbc.query("""
                    SELECT DISTINCT t.task_id, t.created_at
                    FROM relationship_tasks t
                    JOIN relationship_task_evidence e ON e.task_id = t.task_id
                    WHERE t.client_id = :clientId AND e.fingerprint IN (:fingerprints)
                    ORDER BY t.created_at DESC, t.task_id LIMIT 1
                    """, new MapSqlParameterSource("clientId", clientId).addValue("fingerprints", fingerprints),
                    (rs, row) -> rs.getString(1));
            return ids.stream().findFirst().map(JdbcRelationshipTaskRepository.this::loadTask);
        }

        @Override
        public boolean tryReserveTaskCreation(String agentSubject, LocalDate singaporeDate, int limit) {
            return !jdbc.query("""
                    INSERT INTO agent_daily_task_admissions
                        (agent_subject, singapore_date, created_task_count, updated_at)
                    VALUES (?, ?, 1, now())
                    ON CONFLICT (agent_subject, singapore_date) DO UPDATE
                    SET created_task_count = agent_daily_task_admissions.created_task_count + 1,
                        updated_at = now()
                    WHERE agent_daily_task_admissions.created_task_count < ?
                    RETURNING created_task_count
                    """, (rs, row) -> rs.getInt(1), agentSubject, singaporeDate, limit).isEmpty();
        }

        @Override
        public void saveTask(RelationshipTask task, long expectedVersion) {
            int changed;
            if (expectedVersion == 0) {
                changed = jdbc.update("""
                        INSERT INTO relationship_tasks
                            (task_id, client_id, status, severity, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, task.taskId(), task.clientId(), task.status().name(), task.severity().name(),
                        task.version(), Timestamp.from(task.createdAt()), Timestamp.from(task.updatedAt()));
                jdbc.update("""
                        INSERT INTO task_assignment_snapshots
                            (task_id, client_id, agent_subject, assignment_active,
                             assignment_version, assignment_updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, task.taskId(), task.clientId(), task.assignment().agentSubject(),
                        task.assignment().active(), task.assignment().assignmentVersion(),
                        Timestamp.from(task.assignment().updatedAt()));
            } else {
                changed = jdbc.update("""
                        UPDATE relationship_tasks SET status = ?, severity = ?, version = ?, updated_at = ?
                        WHERE task_id = ? AND version = ?
                        """, task.status().name(), task.severity().name(), task.version(),
                        Timestamp.from(task.updatedAt()), task.taskId(), expectedVersion);
                if (changed == 1) jdbc.update("""
                        UPDATE task_assignment_snapshots
                        SET agent_subject = ?, assignment_active = ?, assignment_version = ?,
                            assignment_updated_at = ? WHERE task_id = ?
                        """, task.assignment().agentSubject(), task.assignment().active(),
                        task.assignment().assignmentVersion(), Timestamp.from(task.assignment().updatedAt()),
                        task.taskId());
            }
            if (changed != 1) throw new ConflictException("Task version changed");
            task.evidence().forEach(item -> insertEvidence(task, item));
            task.history().forEach(item -> insertHistory(task.taskId(), item));
        }

        @Override
        public void saveProcessedFinding(ProcessedFinding processed) {
            jdbc.update("""
                    INSERT INTO processed_client_health_findings
                        (finding_id, task_id, finding_payload, outcome, result_version, processed_at)
                    VALUES (?, ?, ?::jsonb, ?, ?, ?)
                    """, processed.finding().findingId(), processed.result().taskId(), write(processed.finding()),
                    processed.result().outcome().name(), processed.result().taskVersion(),
                    Timestamp.from(processed.processedAt()));
        }

        @Override
        public void enqueueAudit(RelationshipHealthAuditEvent event) {
            auditOutbox.enqueue(event);
        }
    }

    private void insertEvidence(RelationshipTask task, Evidence item) {
        SignalEvidence<? extends EvidenceDetails> signal = item.signal();
        int changed = jdbc.update("""
                INSERT INTO relationship_task_evidence
                    (task_id, client_id, finding_id, analysis_run_id, ruleset_version, correlation_id,
                     finding_detected_at, attached_at, signal_id, account_id, signal_type, rule_version,
                     severity, baseline_from, baseline_to, comparison_from, comparison_to,
                     evidence_payload, fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (client_id, fingerprint) DO UPDATE
                SET fingerprint = EXCLUDED.fingerprint
                WHERE relationship_task_evidence.task_id = EXCLUDED.task_id
                """, task.taskId(), task.clientId(), item.findingId(), item.analysisRunId(), item.rulesetVersion(),
                item.correlationId(), Timestamp.from(item.detectedAt()), Timestamp.from(item.attachedAt()),
                signal.signalId(), signal.accountId(), signal.type().name(), signal.ruleVersion(),
                signal.severity().name(), Timestamp.from(signal.window().baselineStart()),
                Timestamp.from(signal.window().baselineEnd()), Timestamp.from(signal.window().comparisonStart()),
                Timestamp.from(signal.window().comparisonEnd()), write(signal.evidence()), signal.fingerprint());
        if (changed != 1) throw new ConflictException("Evidence fingerprint belongs to another task");
    }

    private void insertHistory(String taskId, HistoryEntry item) {
        jdbc.update("""
                INSERT INTO relationship_task_history
                    (task_id, task_version, action, actor_subject, correlation_id, occurred_at,
                     status_before, status_after, severity_before, severity_after, added_fingerprints,
                     disposition_code, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?)
                ON CONFLICT (task_id, task_version) DO NOTHING
                """, taskId, item.taskVersion(), item.action().name(), item.actor(), item.correlationId(),
                Timestamp.from(item.occurredAt()), name(item.beforeStatus()), item.afterStatus().name(),
                name(item.beforeSeverity()), item.afterSeverity().name(), item.addedFingerprints().toArray(String[]::new),
                item.dispositionCode(), item.note());
    }

    private Evidence evidence(ResultSet rs) throws SQLException {
        SignalType type = SignalType.valueOf(rs.getString("signal_type"));
        EvidenceDetails details = readEvidence(rs.getString("evidence_payload"), type);
        SignalEvidence<EvidenceDetails> signal = new SignalEvidence<>(rs.getString("signal_id"),
                rs.getString("account_id"), type, rs.getString("rule_version"),
                Severity.valueOf(rs.getString("severity")), new SignalWindow(instant(rs, "baseline_from"),
                instant(rs, "baseline_to"), instant(rs, "comparison_from"), instant(rs, "comparison_to")),
                details, rs.getString("fingerprint"));
        return new Evidence(rs.getString("finding_id"), rs.getString("analysis_run_id"),
                rs.getString("ruleset_version"), rs.getString("correlation_id"),
                instant(rs, "finding_detected_at"), instant(rs, "attached_at"), signal);
    }

    private HistoryEntry history(ResultSet rs) throws SQLException {
        Array fingerprints = rs.getArray("added_fingerprints");
        String[] values = fingerprints == null ? new String[0] : (String[]) fingerprints.getArray();
        return new HistoryEntry(rs.getLong("task_version"), Action.valueOf(rs.getString("action")),
                rs.getString("actor_subject"), rs.getString("correlation_id"), instant(rs, "occurred_at"),
                status(rs.getString("status_before")), Status.valueOf(rs.getString("status_after")),
                severity(rs.getString("severity_before")), Severity.valueOf(rs.getString("severity_after")),
                List.of(values), rs.getString("disposition_code"), rs.getString("note"));
    }

    ClientHealthFinding readFinding(String value) {
        try {
            JsonNode root = json.readTree(value);
            List<SignalEvidence<? extends EvidenceDetails>> signals = new ArrayList<>();
            for (JsonNode item : root.path("signals")) {
                SignalType type = SignalType.valueOf(item.path("type").asText());
                signals.add(new SignalEvidence<>(item.path("signalId").asText(), item.path("accountId").asText(),
                        type, item.path("ruleVersion").asText(), Severity.valueOf(item.path("severity").asText()),
                        json.treeToValue(item.path("window"), SignalWindow.class),
                        readEvidence(item.path("evidence").toString(), type), item.path("fingerprint").asText()));
            }
            return new ClientHealthFinding(root.path("schemaVersion").asText(), root.path("findingId").asText(),
                    root.path("clientId").asText(), root.path("analysisRunId").asText(),
                    root.path("rulesetVersion").asText(), json.treeToValue(root.path("analysisWindow"), AnalysisWindow.class),
                    signals, json.treeToValue(root.path("priorityInputs"), PriorityInputs.class),
                    root.path("correlationId").asText(),
                    json.treeToValue(root.path("detectedAt"), Instant.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored finding JSON is invalid", exception);
        }
    }

    private EvidenceDetails readEvidence(String value, SignalType type) {
        try {
            return switch (type) {
                case RECURRING_CREDIT_INTERRUPTION -> json.readValue(value,
                        ClientHealthFinding.RecurringCreditInterruptionEvidence.class);
                case TRANSACTION_ACTIVITY_DECLINE -> json.readValue(value,
                        ClientHealthFinding.TransactionActivityDeclineEvidence.class);
                case SUSTAINED_NET_OUTFLOW -> json.readValue(value,
                        ClientHealthFinding.SustainedNetOutflowEvidence.class);
            };
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored evidence JSON is invalid", exception);
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot serialize task data", exception); }
    }

    private CursorBoundary decodeCursor(String cursor) {
        if (cursor == null) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            JsonNode value = json.readTree(new String(decoded, StandardCharsets.UTF_8));
            Instant updatedAt = Instant.parse(value.path("updatedAt").asText());
            String taskId = value.path("taskId").asText();
            if (taskId.isBlank() || taskId.length() > 128) throw new IllegalArgumentException();
            return new CursorBoundary(updatedAt, taskId);
        } catch (RuntimeException | JsonProcessingException invalid) {
            throw new InvalidCursorException();
        }
    }

    private String encodeCursor(CursorBoundary cursor) {
        try {
            byte[] value = json.writeValueAsBytes(Map.of(
                    "updatedAt", cursor.updatedAt().toString(), "taskId", cursor.taskId()));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Cannot encode task cursor", impossible);
        }
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
    private static String name(Enum<?> value) { return value == null ? null : value.name(); }
    private static Status status(String value) { return value == null ? null : Status.valueOf(value); }
    private static Severity severity(String value) { return value == null ? null : Severity.valueOf(value); }

    private record TaskRow(String taskId, String clientId, Status status, Severity severity,
                           long version, Instant createdAt, Instant updatedAt) { }
    private record CursorBoundary(Instant updatedAt, String taskId) { }
}
