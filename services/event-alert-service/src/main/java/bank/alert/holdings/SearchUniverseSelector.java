package bank.alert.holdings;

import bank.alert.config.IngestionProperties;
import bank.alert.domain.HoldingSnapshot;
import bank.alert.operations.OperationalMetrics;
import bank.alert.scoring.ExposureCalculator;
import bank.alert.scoring.ExposureStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Chooses which instruments are worth a public news search: those held by enough of
 * the distinct clients, or making up a large share of one account. Works on
 * complete account portfolios, so the concentration denominator is the whole account.
 */
public class SearchUniverseSelector {

    private static final int SHARE_SCALE = 8;

    private final IngestionProperties properties;
    private final ExposureCalculator exposureCalculator;
    private final OperationalMetrics metrics;

    public SearchUniverseSelector(IngestionProperties properties, ExposureCalculator exposureCalculator,
            OperationalMetrics metrics) {
        this.properties = properties;
        this.exposureCalculator = exposureCalculator;
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
        Set<String> concentrated = held.stream()
                .collect(Collectors.groupingBy(HoldingSnapshot::getAccountRef))
                .values().stream()
                .flatMap(account -> account.stream().map(position -> Map.entry(position.getInstrumentId(),
                        exposureCalculator.calculate(account, Set.of(position.getInstrumentId())))))
                .filter(entry -> entry.getValue().status() == ExposureStatus.EVALUABLE)
                .filter(entry -> entry.getValue().portfolioPercentage().compareTo(
                        properties.minimumAccountConcentration().movePointRight(2)) >= 0)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        List<String> eligible = Stream.concat(common.stream(), concentrated.stream()).distinct()
                .sorted().toList();
        metrics.searchUniverse(eligible.size(), common.size(), concentrated.size());
        return eligible;
    }

    private static BigDecimal clientShare(int clients, BigDecimal clientTotal) {
        return BigDecimal.valueOf(clients).divide(clientTotal, SHARE_SCALE, RoundingMode.HALF_UP);
    }
}
