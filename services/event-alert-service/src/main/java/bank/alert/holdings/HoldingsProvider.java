package bank.alert.holdings;

import java.util.List;
import java.util.Set;

public interface HoldingsProvider {

    HoldingsReadResult read(HoldingsReadRequest request);

    /**
     * The agents whose clients hold any of these instruments, sorted, so one event
     * can be fanned out to each of them (ADR-0049). Reads holdings only; nothing
     * about them leaves the service.
     */
    List<String> assignedAgentsHolding(Set<String> instrumentIds);
}
