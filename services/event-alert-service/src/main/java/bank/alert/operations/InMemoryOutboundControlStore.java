package bank.alert.operations;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Single-process store for unit tests; production uses {@link JdbcOutboundControlStore}. */
public class InMemoryOutboundControlStore implements OutboundControlStore {
    private record Usage(int searches, long tokens, BigDecimal cost) { }

    private final Map<YearMonth, Usage> usage = new HashMap<>();
    private final Map<OutboundCapability, ControlReason> disabled =
            new EnumMap<>(OutboundCapability.class);

    @Override
    public synchronized OperationalUsageSnapshot usage(YearMonth month) {
        Usage current = current(month);
        return new OperationalUsageSnapshot(month, current.searches(), current.tokens(), current.cost());
    }

    @Override
    public synchronized boolean reserveSearches(YearMonth month, int requested, long monthlyQuota) {
        Usage current = current(month);
        if ((long) current.searches() + requested > monthlyQuota) return false;
        usage.put(month, new Usage(current.searches() + requested, current.tokens(), current.cost()));
        return true;
    }

    @Override
    public synchronized boolean reserveModelTokens(YearMonth month, long requested, long monthlyQuota) {
        Usage current = current(month);
        if (current.tokens() + requested > monthlyQuota) return false;
        usage.put(month, new Usage(current.searches(), current.tokens() + requested, current.cost()));
        return true;
    }

    @Override
    public synchronized BigDecimal addEstimatedCost(YearMonth month, BigDecimal incrementUsd) {
        Usage current = current(month);
        BigDecimal total = current.cost().add(incrementUsd);
        usage.put(month, new Usage(current.searches(), current.tokens(), total));
        return total;
    }

    @Override
    public synchronized Optional<ControlReason> disabledReason(OutboundCapability capability) {
        return Optional.ofNullable(disabled.get(capability));
    }

    @Override
    public synchronized Optional<ControlReason> disable(OutboundCapability capability,
            ControlReason reason) {
        return Optional.ofNullable(disabled.put(capability, reason));
    }

    @Override
    public synchronized void clearDisabled(OutboundCapability capability) {
        disabled.remove(capability);
    }

    private Usage current(YearMonth month) {
        return usage.getOrDefault(month, new Usage(0, 0, BigDecimal.ZERO));
    }
}
