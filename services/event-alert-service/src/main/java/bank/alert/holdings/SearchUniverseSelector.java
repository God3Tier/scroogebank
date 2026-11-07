package bank.alert.holdings;

import bank.alert.config.IngestionProperties;
import bank.alert.domain.HoldingSnapshot;
import bank.alert.operations.OperationalMetrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Chooses which instruments are worth a public news search: those held by enough of
 * the distinct clients: at least the minimum count of them, and at least the minimum share of the clients that hold
 * anything. A large share of one account no longer qualifies a company on its own.
 */
public class SearchUniverseSelector {

    private static final int SHARE_SCALE = 8;

    private final IngestionProperties properties;
    private final OperationalMetrics metrics;

    public SearchUniverseSelector(IngestionProperties properties, OperationalMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    public List<String> select(List<HoldingSnapshot> positions) {
        List<HoldingSnapshot> held = positions.stream()
                .filter(position -> position.getQuantity().signum() > 0)
                .filter(position -> position.getValueSgd().signum() > 0)
                .toList();
        Set<String> clients = held.stream().map(HoldingSnapshot::getClientRef)
                .collect(Collectors.toUnmodifiableSet());
        if (clients.isEmpty()) return List.of();

        Map<String, Set<String>> clientsByInstrument = held.stream()
                .collect(Collectors.groupingBy(HoldingSnapshot::getInstrumentId,
                        Collectors.mapping(HoldingSnapshot::getClientRef, Collectors.toSet())));

        BigDecimal clientTotal = BigDecimal.valueOf(clients.size());
        Set<String> common = clientsByInstrument.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= properties.minimumSearchClientCount()
                        && clientShare(entry.getValue().size(), clientTotal)
                        .compareTo(properties.minimumSearchClientShare()) >= 0)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        List<String> eligible = common.stream().sorted().toList();
        metrics.searchUniverse(eligible.size(), common.size());
        return eligible;
    }

    private static BigDecimal clientShare(int clients, BigDecimal clientTotal) {
        return BigDecimal.valueOf(clients).divide(clientTotal, SHARE_SCALE, RoundingMode.HALF_UP);
    }
}
