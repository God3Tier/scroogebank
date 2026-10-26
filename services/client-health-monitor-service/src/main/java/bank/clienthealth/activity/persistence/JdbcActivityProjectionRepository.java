package bank.clienthealth.activity.persistence;

import bank.clienthealth.activity.domain.ProjectedTransaction;
import bank.clienthealth.activity.port.ActivityProjectionRepository;
import bank.clienthealth.transaction.contract.MatchedTransaction.ActivityKind;
import bank.clienthealth.transaction.contract.MatchedTransaction.Status;
import bank.clienthealth.transaction.contract.MatchedTransaction.TransactionType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL adapter for the minimum service-owned activity projection. */
public final class JdbcActivityProjectionRepository implements ActivityProjectionRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcActivityProjectionRepository(
            JdbcTemplate jdbc,
            TransactionTemplate transactions
    ) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.transactions = java.util.Objects.requireNonNull(transactions);
    }

    @Override
    public <T> T inTransaction(Function<Session, T> work) {
        try {
            return transactions.execute(status -> work.apply(new JdbcSession()));
        } catch (DataIntegrityViolationException conflict) {
            throw new ProjectionPersistenceException(
                    "Activity projection constraint conflict", conflict);
        }
    }

    private final class JdbcSession implements Session {
        @Override
        public Optional<ProjectedTransaction> findById(String transactionId) {
            return jdbc.query("""
                    SELECT * FROM client_health_activity_projection
                    WHERE transaction_id = ?
                    """, (rs, row) -> projected(rs), transactionId).stream().findFirst();
        }

        @Override
        public void insert(ProjectedTransaction transaction) {
            jdbc.update("""
                    INSERT INTO client_health_activity_projection
                        (transaction_id, client_id, account_id, transaction_type, amount,
                         currency, transaction_date, status, activity_kind, source_fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, transaction.transactionId(), transaction.clientId(),
                    transaction.accountId(), transaction.type().name(), transaction.amount(),
                    transaction.currency(), Timestamp.from(transaction.transactionDate()),
                    transaction.status().name(), transaction.activityKind().name(),
                    transaction.sourceFingerprint());
        }

        @Override
        public void replace(ProjectedTransaction transaction) {
            int changed = jdbc.update("""
                    UPDATE client_health_activity_projection
                    SET status = ?
                    WHERE transaction_id = ? AND status = 'PENDING'
                    """, transaction.status().name(), transaction.transactionId());
            if (changed != 1) {
                throw new ProjectionPersistenceException(
                        "Projected transaction does not exist: " + transaction.transactionId());
            }
        }

        @Override
        public List<ProjectedTransaction> findWindow(
                Instant fromInclusive,
                Instant toExclusive
        ) {
            return jdbc.query("""
                    SELECT * FROM client_health_activity_projection
                    WHERE transaction_date >= ? AND transaction_date < ?
                    ORDER BY transaction_date, transaction_id
                    """, (rs, row) -> projected(rs),
                    Timestamp.from(fromInclusive), Timestamp.from(toExclusive));
        }

        @Override
        public void recordCoverage(Instant fromInclusive, Instant toExclusive) {
            jdbc.update("""
                    INSERT INTO client_health_replay_coverage
                        (from_inclusive, to_exclusive)
                    VALUES (?, ?)
                    ON CONFLICT (from_inclusive, to_exclusive) DO NOTHING
                    """, Timestamp.from(fromInclusive), Timestamp.from(toExclusive));
        }

        @Override
        public boolean covers(Instant fromInclusive, Instant toExclusive) {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM client_health_replay_coverage
                        WHERE from_inclusive <= ? AND to_exclusive >= ?
                    )
                    """, Boolean.class, Timestamp.from(fromInclusive), Timestamp.from(toExclusive)));
        }
    }

    private static ProjectedTransaction projected(ResultSet rs) throws SQLException {
        return new ProjectedTransaction(
                rs.getString("transaction_id"),
                rs.getString("client_id"),
                rs.getString("account_id"),
                TransactionType.valueOf(rs.getString("transaction_type")),
                rs.getBigDecimal("amount"),
                rs.getString("currency").trim(),
                instant(rs, "transaction_date"),
                Status.valueOf(rs.getString("status")),
                ActivityKind.valueOf(rs.getString("activity_kind")),
                rs.getString("source_fingerprint"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    public static final class ProjectionPersistenceException extends RuntimeException {
        public ProjectionPersistenceException(String message) {
            super(message);
        }

        public ProjectionPersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
