package bank.alert.operations;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Where monthly outbound usage and disabled capabilities are kept. The worker is
 * a new process every run and the API is another, so the production store is the
 * database; each operation must be atomic across processes.
 */
public interface OutboundControlStore {
    OperationalUsageSnapshot usage(YearMonth month);

    /** Adds the searches only if the monthly total stays within the quota. */
    boolean reserveSearches(YearMonth month, int requested, long monthlyQuota);

    /** Adds the tokens only if the monthly total stays within the quota. */
    boolean reserveModelTokens(YearMonth month, long requested, long monthlyQuota);

    /** Adds the cost and returns the month's new total. */
    BigDecimal addEstimatedCost(YearMonth month, BigDecimal incrementUsd);

    Optional<ControlReason> disabledReason(OutboundCapability capability);

    /** Records the reason and returns the one it replaced, if any. */
    Optional<ControlReason> disable(OutboundCapability capability, ControlReason reason);

    void clearDisabled(OutboundCapability capability);
}
