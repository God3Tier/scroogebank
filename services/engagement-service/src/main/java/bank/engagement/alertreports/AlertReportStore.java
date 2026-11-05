package bank.engagement.alertreports;

import bank.engagement.alertreports.AlertReportModels.RecipientStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The batch and delivery tables (ADR-0084). Plain SQL: this module owns these tables and nothing else reads them. */
@Component
class AlertReportStore {

    record Batch(UUID id, String requestHash, UUID eventId, UUID reportId, int reportVersion, String reportHash,
            String snapshot, String agentNote) {
    }

    record Claimed(UUID id, UUID alertId, String clientRef, String issuer, int attempts) {
    }

    private final JdbcTemplate jdbc;

    AlertReportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Batch> findBatch(String owner, String key) {
        return jdbc.query("""
                SELECT id, request_hash, event_id, report_id, report_version, report_hash, snapshot::text, agent_note
                  FROM alert_report_batch WHERE owner_agent_sub = ? AND idempotency_key = ?
                """, (rs, n) -> new Batch(rs.getObject(1, UUID.class), rs.getString(2).strip(), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getInt(5), rs.getString(6).strip(), rs.getString(7), rs.getString(8)),
                owner, key).stream().findFirst();
    }

    void insertBatch(Batch batch, String owner, String key, String noteHash, Instant now) {
        jdbc.update("""
                INSERT INTO alert_report_batch (id, owner_agent_sub, idempotency_key, request_hash, event_id, report_id,
                    report_version, report_hash, snapshot, agent_note, note_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                """, batch.id(), owner, key, batch.requestHash(), batch.eventId(), batch.reportId(), batch.reportVersion(),
                batch.reportHash(), batch.snapshot(), batch.agentNote(), noteHash, Timestamp.from(now));
    }

    boolean alreadyDelivered(UUID reportId, UUID alertId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM alert_report_delivery
                 WHERE report_id = ? AND alert_id = ? AND status IN ('QUEUED', 'SENT')
                """, Integer.class, reportId, alertId);
        return count != null && count > 0;
    }

    void insertDelivery(int position, UUID batchId, UUID reportId, UUID alertId, String clientRef, String instrumentId, String issuer,
            String status, String errorCode, Instant now) {
        jdbc.update("""
                INSERT INTO alert_report_delivery (id, batch_id, report_id, alert_id, position, client_ref, instrument_id, issuer_name,
                    status, last_error_code, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), batchId, reportId, alertId, position, clientRef, instrumentId, issuer, status, errorCode,
                Timestamp.from(now), Timestamp.from(now));
    }

    /** Leases the next recipient still queued, so two requests never send to the same client at once. */
    Optional<Claimed> claimNext(UUID batchId, Instant now, Duration lease, java.util.Collection<UUID> skip) {
        String skipped = "{" + String.join(",", skip.stream().map(UUID::toString).toList()) + "}";
        return jdbc.query("""
                UPDATE alert_report_delivery SET leased_until = ?, updated_at = ?
                 WHERE id = (SELECT id FROM alert_report_delivery
                              WHERE batch_id = ? AND status = 'QUEUED' AND (leased_until IS NULL OR leased_until < ?)
                                AND id <> ALL (CAST(? AS uuid[]))
                              ORDER BY position LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING id, alert_id, client_ref, issuer_name, attempts
                """, (rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getInt(5)),
                Timestamp.from(now.plus(lease)), Timestamp.from(now), batchId, Timestamp.from(now), skipped).stream().findFirst();
    }

    void markSent(UUID id, String messageId, Instant now) {
        jdbc.update("""
                UPDATE alert_report_delivery SET status = 'SENT', ses_message_id = ?, sent_at = ?, attempts = attempts + 1,
                    leased_until = NULL, last_error_code = NULL, updated_at = ? WHERE id = ?
                """, messageId, Timestamp.from(now), Timestamp.from(now), id);
    }

    void markFinal(UUID id, String status, String errorCode, Instant now) {
        jdbc.update("""
                UPDATE alert_report_delivery SET status = ?, last_error_code = ?, leased_until = NULL, updated_at = ?
                 WHERE id = ?
                """, status, errorCode, Timestamp.from(now), id);
    }

    /** One more failed attempt: FAILED when none is left, otherwise still QUEUED. Returns the new status. */
    String retryLater(UUID id, String errorCode, int maxAttempts, Instant now) {
        return jdbc.queryForObject("""
                UPDATE alert_report_delivery
                   SET attempts = attempts + 1, last_error_code = ?, leased_until = NULL, updated_at = ?,
                       status = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'QUEUED' END
                 WHERE id = ? RETURNING status
                """, String.class, errorCode, Timestamp.from(now), maxAttempts, id);
    }

    int queued(UUID batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM alert_report_delivery WHERE batch_id = ? AND status = 'QUEUED'", Integer.class, batchId);
        return count == null ? 0 : count;
    }

    /** Drops the report text and the note once nothing is left to send; hashes, IDs and statuses stay. */
    void clearContent(UUID batchId, Instant now) {
        jdbc.update("""
                UPDATE alert_report_batch SET snapshot = NULL, agent_note = NULL, content_cleared_at = ?
                 WHERE id = ? AND content_cleared_at IS NULL
                """, Timestamp.from(now), batchId);
    }

    List<RecipientStatus> statuses(UUID batchId) {
        return jdbc.query("""
                SELECT alert_id, status, last_error_code FROM alert_report_delivery
                 WHERE batch_id = ? ORDER BY position
                """, (rs, n) -> new RecipientStatus(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), batchId);
    }
}
