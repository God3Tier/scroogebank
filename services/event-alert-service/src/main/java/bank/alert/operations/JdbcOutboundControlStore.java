package bank.alert.operations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;

/**
 * PostgreSQL store shared by the API and every worker run. Each operation runs in
 * its own transaction, so a reservation is kept even if the caller's transaction
 * later rolls back (the outbound call may already have been made), and concurrent
 * processes cannot take more than the quota.
 */
@Repository
public class JdbcOutboundControlStore implements OutboundControlStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcOutboundControlStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public OperationalUsageSnapshot usage(YearMonth month) {
        return transaction.execute(status -> jdbc.query("""
                SELECT search_queries, model_tokens, estimated_cost_usd
                FROM outbound_usage WHERE usage_month = ?
                """, (rs, row) -> new OperationalUsageSnapshot(month, rs.getInt(1), rs.getLong(2),
                rs.getBigDecimal(3)), month.toString()).stream().findFirst()
                .orElseGet(() -> new OperationalUsageSnapshot(month, 0, 0, BigDecimal.ZERO)));
    }

    @Override
    public boolean reserveSearches(YearMonth month, int requested, long monthlyQuota) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            ensureMonth(month);
            return jdbc.update("""
                    UPDATE outbound_usage
                    SET search_queries = search_queries + ?, updated_at = now()
                    WHERE usage_month = ? AND search_queries + ? <= ?
                    """, requested, month.toString(), requested, monthlyQuota) == 1;
        }));
    }

    @Override
    public boolean reserveModelTokens(YearMonth month, long requested, long monthlyQuota) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            ensureMonth(month);
            return jdbc.update("""
                    UPDATE outbound_usage
                    SET model_tokens = model_tokens + ?, updated_at = now()
                    WHERE usage_month = ? AND model_tokens + ? <= ?
                    """, requested, month.toString(), requested, monthlyQuota) == 1;
        }));
    }

    @Override
    public BigDecimal addEstimatedCost(YearMonth month, BigDecimal incrementUsd) {
        return transaction.execute(status -> {
            ensureMonth(month);
            return jdbc.queryForObject("""
                    UPDATE outbound_usage
                    SET estimated_cost_usd = estimated_cost_usd + ?, updated_at = now()
                    WHERE usage_month = ?
                    RETURNING estimated_cost_usd
                    """, BigDecimal.class, incrementUsd, month.toString());
        });
    }

    @Override
    public Optional<ControlReason> disabledReason(OutboundCapability capability) {
        return transaction.execute(status -> jdbc.query(
                "SELECT reason FROM outbound_control WHERE capability = ?",
                (rs, row) -> ControlReason.valueOf(rs.getString(1)),
                capability.name()).stream().findFirst());
    }

    @Override
    public Optional<ControlReason> disable(OutboundCapability capability, ControlReason reason) {
        return transaction.execute(status -> {
            Optional<ControlReason> previous = jdbc.query(
                    "SELECT reason FROM outbound_control WHERE capability = ? FOR UPDATE",
                    (rs, row) -> ControlReason.valueOf(rs.getString(1)),
                    capability.name()).stream().findFirst();
            jdbc.update("""
                    INSERT INTO outbound_control (capability, reason) VALUES (?, ?)
                    ON CONFLICT (capability) DO UPDATE
                        SET reason = EXCLUDED.reason, updated_at = now()
                    """, capability.name(), reason.name());
            return previous;
        });
    }

    @Override
    public void clearDisabled(OutboundCapability capability) {
        transaction.executeWithoutResult(status -> jdbc.update(
                "DELETE FROM outbound_control WHERE capability = ?", capability.name()));
    }

    private void ensureMonth(YearMonth month) {
        jdbc.update("INSERT INTO outbound_usage (usage_month) VALUES (?) ON CONFLICT DO NOTHING",
                month.toString());
    }
}
