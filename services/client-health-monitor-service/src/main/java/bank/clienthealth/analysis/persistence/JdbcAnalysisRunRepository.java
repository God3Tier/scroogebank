package bank.clienthealth.analysis.persistence;

import bank.clienthealth.analysis.port.AnalysisRunPort;
import bank.clienthealth.audit.AuditOutboxRepository;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.Action;
import bank.clienthealth.audit.RelationshipHealthAuditEventFactory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL lease and success-watermark adapter for RH-SF04 analysis runs. */
public final class JdbcAnalysisRunRepository implements AnalysisRunPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Supplier<String> leaseTokens;
    private final AuditOutboxRepository auditOutbox;

    public JdbcAnalysisRunRepository(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            Supplier<String> leaseTokens,
            AuditOutboxRepository auditOutbox
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.leaseTokens = Objects.requireNonNull(leaseTokens);
        this.auditOutbox = Objects.requireNonNull(auditOutbox);
    }

    @Override
    public Acquisition acquire(
            String runId,
            Instant analysisCutoff,
            Instant now,
            Duration leaseDuration
    ) {
        requireLeaseDuration(leaseDuration);
        return transactions.execute(status -> {
            String token = Objects.requireNonNull(leaseTokens.get(), "lease token is required");
            Instant expiresAt = now.plus(leaseDuration);
            int inserted = jdbc.update("""
                    INSERT INTO relationship_health_analysis_runs
                        (run_id, analysis_cutoff, status, lease_token, lease_expires_at,
                         attempts, watermark, failure_category, created_at, updated_at)
                    VALUES (?, ?, 'RUNNING', ?, ?, 1, NULL, NULL, ?, ?)
                    ON CONFLICT (run_id) DO NOTHING
                    """, runId, Timestamp.from(analysisCutoff), token,
                    Timestamp.from(expiresAt), Timestamp.from(now), Timestamp.from(now));
            RunRow row = lock(runId);
            if (!row.analysisCutoff().equals(analysisCutoff)) {
                throw new LeaseConflictException("run ID reused with another analysis cutoff");
            }
            if (inserted == 1) {
                auditOutbox.enqueue(RelationshipHealthAuditEventFactory.analysis(
                        runId, Action.ANALYSIS_RUN_STARTED, now, null));
                return new Acquisition(
                        AcquisitionStatus.ACQUIRED, new Lease(runId, token, expiresAt));
            }
            if (row.status() == RunStatus.SUCCEEDED) {
                return new Acquisition(AcquisitionStatus.ALREADY_SUCCEEDED, null);
            }
            if (row.status() == RunStatus.RUNNING && row.leaseExpiresAt().isAfter(now)) {
                return new Acquisition(AcquisitionStatus.LEASE_HELD, null);
            }
            int changed = jdbc.update("""
                    UPDATE relationship_health_analysis_runs
                    SET status = 'RUNNING', lease_token = ?, lease_expires_at = ?,
                        attempts = attempts + 1, watermark = NULL,
                        failure_category = NULL, updated_at = ?
                    WHERE run_id = ?
                    """, token, Timestamp.from(expiresAt), Timestamp.from(now), runId);
            if (changed != 1) {
                throw new LeaseConflictException("analysis run could not be recovered");
            }
            auditOutbox.enqueue(RelationshipHealthAuditEventFactory.analysis(
                    runId, Action.ANALYSIS_RUN_STARTED, now, null));
            return new Acquisition(
                    AcquisitionStatus.ACQUIRED, new Lease(runId, token, expiresAt));
        });
    }

    @Override
    public Lease renew(Lease lease, Instant now, Duration leaseDuration) {
        requireLeaseDuration(leaseDuration);
        Instant expiresAt = now.plus(leaseDuration);
        int changed = jdbc.update("""
                UPDATE relationship_health_analysis_runs
                SET lease_expires_at = ?, updated_at = ?
                WHERE run_id = ? AND status = 'RUNNING' AND lease_token = ?
                  AND lease_expires_at > ?
                """, Timestamp.from(expiresAt), Timestamp.from(now),
                lease.runId(), lease.token(), Timestamp.from(now));
        if (changed != 1) {
            throw new LeaseConflictException("analysis lease is not live");
        }
        return new Lease(lease.runId(), lease.token(), expiresAt);
    }

    @Override
    public void succeed(Lease lease, Instant watermark, Instant now) {
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE relationship_health_analysis_runs
                    SET status = 'SUCCEEDED', lease_token = NULL, lease_expires_at = NULL,
                        watermark = ?, failure_category = NULL, updated_at = ?
                    WHERE run_id = ? AND status = 'RUNNING' AND lease_token = ?
                      AND lease_expires_at > ?
                    """, Timestamp.from(watermark), Timestamp.from(now),
                    lease.runId(), lease.token(), Timestamp.from(now));
            if (changed != 1) {
                throw new LeaseConflictException("analysis lease cannot complete the run");
            }
            auditOutbox.enqueue(RelationshipHealthAuditEventFactory.analysis(
                    lease.runId(), Action.ANALYSIS_RUN_COMPLETED, now, null));
        });
    }

    @Override
    public void fail(Lease lease, FailureCategory category, Instant now) {
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE relationship_health_analysis_runs
                    SET status = 'FAILED', lease_token = NULL, lease_expires_at = NULL,
                        watermark = NULL, failure_category = ?, updated_at = ?
                    WHERE run_id = ? AND status = 'RUNNING' AND lease_token = ?
                    """, category.name(), Timestamp.from(now), lease.runId(), lease.token());
            if (changed != 1) {
                throw new LeaseConflictException("analysis lease cannot fail the run");
            }
            auditOutbox.enqueue(RelationshipHealthAuditEventFactory.analysis(
                    lease.runId(), Action.ANALYSIS_RUN_FAILED, now, category.name()));
        });
    }

    private RunRow lock(String runId) {
        List<RunRow> rows = jdbc.query("""
                SELECT * FROM relationship_health_analysis_runs
                WHERE run_id = ? FOR UPDATE
                """, (rs, index) -> row(rs), runId);
        if (rows.isEmpty()) {
            throw new LeaseConflictException("analysis run does not exist");
        }
        return rows.getFirst();
    }

    private static RunRow row(ResultSet rs) throws SQLException {
        return new RunRow(
                RunStatus.valueOf(rs.getString("status")),
                instant(rs, "analysis_cutoff"),
                nullableInstant(rs, "lease_expires_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static void requireLeaseDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
    }

    private enum RunStatus { RUNNING, SUCCEEDED, FAILED }

    private record RunRow(
            RunStatus status,
            Instant analysisCutoff,
            Instant leaseExpiresAt
    ) { }

    public static final class LeaseConflictException extends RuntimeException {
        public LeaseConflictException(String message) {
            super(message);
        }
    }
}
