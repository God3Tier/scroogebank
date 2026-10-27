package bank.clienthealth.audit;

import bank.clienthealth.audit.RelationshipHealthAuditEvent.Action;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.Payload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import bank.clienthealth.metrics.ClientHealthMetrics;
import bank.clienthealth.metrics.ClientHealthMetrics.AuditFailure;
import bank.clienthealth.metrics.ClientHealthMetrics.AuditOutcome;

/** PostgreSQL implementation of the leased relationship-health audit outbox. */
public final class JdbcAuditOutboxRepository implements AuditOutboxRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Supplier<String> leaseTokens;
    private final ClientHealthMetrics metrics;

    public JdbcAuditOutboxRepository(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ObjectMapper json,
            Supplier<String> leaseTokens
    ) {
        this(jdbc, transactions, json, leaseTokens, ClientHealthMetrics.noOp());
    }

    public JdbcAuditOutboxRepository(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json,
            Supplier<String> leaseTokens, ClientHealthMetrics metrics
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.json = Objects.requireNonNull(json);
        this.leaseTokens = Objects.requireNonNull(leaseTokens);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public void enqueue(RelationshipHealthAuditEvent event) {
        String payload = write(event.payload());
        int changed = jdbc.update("""
                INSERT INTO relationship_health_audit_outbox
                    (event_id, event_type, action, source_service, actor_id, payload,
                     created_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), RelationshipHealthAuditEvent.EVENT_TYPE,
                event.action().name(), RelationshipHealthAuditEvent.SOURCE_SERVICE,
                event.actorId(), payload, Timestamp.from(event.payload().occurredAt()),
                Timestamp.from(event.payload().occurredAt()));
        if (changed == 0 && !sameEvent(event, payload)) {
            throw new OutboxConflictException(
                    "Audit event ID reused with different content: " + event.eventId());
        }
        if (changed == 1) afterCommit(() -> metrics.audit(AuditOutcome.ENQUEUED, AuditFailure.NONE));
    }

    @Override
    public List<LeasedAuditEvent> leaseBatch(
            Instant now,
            int limit,
            Duration leaseDuration
    ) {
        validateBounds(limit, leaseDuration);
        return transactions.execute(status -> {
            List<LeaseCandidate> candidates = jdbc.query("""
                    SELECT event_id, lease_token IS NOT NULL AS recovered
                    FROM relationship_health_audit_outbox
                    WHERE (lease_token IS NULL AND next_attempt_at <= ?)
                       OR (lease_token IS NOT NULL AND lease_expires_at <= ?)
                    ORDER BY next_attempt_at, created_at, event_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                    """, (rs, row) -> new LeaseCandidate(rs.getString(1), rs.getBoolean(2)),
                    Timestamp.from(now), Timestamp.from(now), limit);
            List<LeasedAuditEvent> leased = new ArrayList<>();
            for (LeaseCandidate candidate : candidates) {
                String eventId = candidate.eventId();
                String token = Objects.requireNonNull(
                        leaseTokens.get(), "lease token is required");
                Instant expiresAt = now.plus(leaseDuration);
                int changed = jdbc.update("""
                        UPDATE relationship_health_audit_outbox
                        SET lease_token = ?, lease_expires_at = ?,
                            attempt_count = attempt_count + 1
                        WHERE event_id = ?
                        """, token, Timestamp.from(expiresAt), eventId);
                if (changed != 1) {
                    throw new OutboxConflictException("Audit event lease changed");
                }
                leased.add(load(eventId, token, candidate.recovered()));
            }
            return List.copyOf(leased);
        });
    }

    @Override
    public void markDelivered(String eventId, String leaseToken) {
        int changed = jdbc.update("""
                DELETE FROM relationship_health_audit_outbox
                WHERE event_id = ? AND lease_token = ?
                """, eventId, leaseToken);
        if (changed != 1) {
            throw new OutboxConflictException("Audit delivery lease is not current");
        }
    }

    @Override
    public void release(
            String eventId,
            String leaseToken,
            Instant nextAttemptAt,
            FailureCategory category
    ) {
        int changed = jdbc.update("""
                UPDATE relationship_health_audit_outbox
                SET lease_token = NULL, lease_expires_at = NULL,
                    next_attempt_at = ?, last_failure_category = ?
                WHERE event_id = ? AND lease_token = ?
                """, Timestamp.from(nextAttemptAt), category.name(), eventId, leaseToken);
        if (changed != 1) {
            throw new OutboxConflictException("Audit release lease is not current");
        }
    }

    @Override
    public Backlog backlog(Instant now) {
        Objects.requireNonNull(now);
        return jdbc.queryForObject("""
                SELECT count(*) AS pending_count, min(created_at) AS oldest
                FROM relationship_health_audit_outbox
                """, (rs, row) -> {
                    long count = rs.getLong("pending_count");
                    OffsetDateTime oldest = rs.getObject("oldest", OffsetDateTime.class);
                    Duration age = oldest == null ? Duration.ZERO
                            : Duration.between(oldest.toInstant(), now).isNegative()
                                    ? Duration.ZERO : Duration.between(oldest.toInstant(), now);
                    return new Backlog(count, age);
                });
    }

    private LeasedAuditEvent load(String eventId, String leaseToken, boolean recovered) {
        return jdbc.queryForObject("""
                SELECT action, actor_id, payload::text, attempt_count
                FROM relationship_health_audit_outbox
                WHERE event_id = ? AND lease_token = ?
                """, (rs, row) -> leased(rs, eventId, leaseToken, recovered), eventId, leaseToken);
    }

    private LeasedAuditEvent leased(
            ResultSet rs,
            String eventId,
            String leaseToken,
            boolean recovered
    ) throws SQLException {
        RelationshipHealthAuditEvent event = new RelationshipHealthAuditEvent(
                eventId,
                Action.valueOf(rs.getString("action")),
                rs.getString("actor_id"),
                readPayload(rs.getString("payload")));
        return new LeasedAuditEvent(event, leaseToken, rs.getInt("attempt_count"), recovered);
    }

    private boolean sameEvent(RelationshipHealthAuditEvent event, String payload) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM relationship_health_audit_outbox
                WHERE event_id = ? AND event_type = ? AND action = ? AND source_service = ?
                  AND actor_id IS NOT DISTINCT FROM ? AND payload = ?::jsonb
                """, Integer.class, event.eventId(), RelationshipHealthAuditEvent.EVENT_TYPE,
                event.action().name(), RelationshipHealthAuditEvent.SOURCE_SERVICE,
                event.actorId(), payload);
        return count != null && count == 1;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }

    private Payload readPayload(String value) {
        try {
            return json.readValue(value, Payload.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Stored audit payload is invalid", invalid);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot serialize audit payload", invalid);
        }
    }

    private static void validateBounds(int limit, Duration leaseDuration) {
        if (limit < 1 || limit > 100 || leaseDuration == null
                || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("invalid outbox lease bounds");
        }
    }

    public static final class OutboxConflictException extends RuntimeException {
        public OutboxConflictException(String message) {
            super(message);
        }
    }

    private record LeaseCandidate(String eventId, boolean recovered) { }
}
