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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL implementation of the leased relationship-health audit outbox. */
public final class JdbcAuditOutboxRepository implements AuditOutboxRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Supplier<String> leaseTokens;

    public JdbcAuditOutboxRepository(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ObjectMapper json,
            Supplier<String> leaseTokens
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.json = Objects.requireNonNull(json);
        this.leaseTokens = Objects.requireNonNull(leaseTokens);
    }

    @Override
    public void enqueue(RelationshipHealthAuditEvent event) {
        String payload = write(event.payload());
        int changed = jdbc.update("""
                INSERT INTO relationship_health_audit_outbox
                    (event_id, event_type, action, source_service, actor_id, payload,
                     created_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (event_id) DO UPDATE SET event_id = EXCLUDED.event_id
                WHERE relationship_health_audit_outbox.event_type = EXCLUDED.event_type
                  AND relationship_health_audit_outbox.action = EXCLUDED.action
                  AND relationship_health_audit_outbox.source_service = EXCLUDED.source_service
                  AND relationship_health_audit_outbox.actor_id = EXCLUDED.actor_id
                  AND relationship_health_audit_outbox.payload = EXCLUDED.payload
                """, event.eventId(), RelationshipHealthAuditEvent.EVENT_TYPE,
                event.action().name(), RelationshipHealthAuditEvent.SOURCE_SERVICE,
                event.actorId(), payload, Timestamp.from(event.payload().occurredAt()),
                Timestamp.from(event.payload().occurredAt()));
        if (changed != 1) {
            throw new OutboxConflictException(
                    "Audit event ID reused with different content: " + event.eventId());
        }
    }

    @Override
    public List<LeasedAuditEvent> leaseBatch(
            Instant now,
            int limit,
            Duration leaseDuration
    ) {
        validateBounds(limit, leaseDuration);
        return transactions.execute(status -> {
            List<String> eventIds = jdbc.query("""
                    SELECT event_id
                    FROM relationship_health_audit_outbox
                    WHERE (lease_token IS NULL AND next_attempt_at <= ?)
                       OR (lease_token IS NOT NULL AND lease_expires_at <= ?)
                    ORDER BY next_attempt_at, created_at, event_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                    """, (rs, row) -> rs.getString(1),
                    Timestamp.from(now), Timestamp.from(now), limit);
            List<LeasedAuditEvent> leased = new ArrayList<>();
            for (String eventId : eventIds) {
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
                leased.add(load(eventId, token));
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

    private LeasedAuditEvent load(String eventId, String leaseToken) {
        return jdbc.queryForObject("""
                SELECT action, actor_id, payload::text, attempt_count
                FROM relationship_health_audit_outbox
                WHERE event_id = ? AND lease_token = ?
                """, (rs, row) -> leased(rs, eventId, leaseToken), eventId, leaseToken);
    }

    private LeasedAuditEvent leased(
            ResultSet rs,
            String eventId,
            String leaseToken
    ) throws SQLException {
        RelationshipHealthAuditEvent event = new RelationshipHealthAuditEvent(
                eventId,
                Action.valueOf(rs.getString("action")),
                rs.getString("actor_id"),
                readPayload(rs.getString("payload")));
        return new LeasedAuditEvent(event, leaseToken, rs.getInt("attempt_count"));
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
}
