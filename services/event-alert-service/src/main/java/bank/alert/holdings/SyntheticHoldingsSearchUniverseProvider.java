package bank.alert.holdings;

import bank.alert.config.IngestionProperties;
import bank.alert.fixture.SyntheticFixtureLoader;
import bank.alert.fixture.SyntheticHoldingFixture;
import bank.alert.ingestion.SearchUniverseProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SyntheticHoldingsSearchUniverseProvider implements SearchUniverseProvider {

    private static final int SHARE_SCALE = 8;

    private final SyntheticFixtureLoader fixtures;
    private final IngestionProperties properties;

    public SyntheticHoldingsSearchUniverseProvider(
            SyntheticFixtureLoader fixtures, IngestionProperties properties) {
        this.fixtures = fixtures;
        this.properties = properties;
    }

    @Override
    public List<String> eligibleInstrumentIds() {
        List<SyntheticHoldingFixture.Position> heldPositions = fixtures.loadDefault().positions().stream()
                .filter(position -> position.quantity().signum() > 0)
                .filter(position -> position.valueSgd().signum() > 0)
                .toList();
        Set<String> clients = heldPositions.stream()
                .map(SyntheticHoldingFixture.Position::clientRef)
                .collect(Collectors.toUnmodifiableSet());
        if (clients.isEmpty()) return List.of();

        Map<String, Set<String>> clientsByInstrument = heldPositions.stream()
                .collect(Collectors.groupingBy(
                        SyntheticHoldingFixture.Position::instrumentId,
                        Collectors.mapping(SyntheticHoldingFixture.Position::clientRef,
                                Collectors.toSet())));

        BigDecimal clientTotal = BigDecimal.valueOf(clients.size());
        return clientsByInstrument.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= properties.minimumSearchClientCount())
                .filter(entry -> clientShare(entry.getValue().size(), clientTotal)
                        .compareTo(properties.minimumSearchClientShare()) >= 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static BigDecimal clientShare(int clients, BigDecimal clientTotal) {
        return BigDecimal.valueOf(clients).divide(clientTotal, SHARE_SCALE, RoundingMode.HALF_UP);
    }
}
