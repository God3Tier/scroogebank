package bank.alert.holdings;

import bank.alert.config.IngestionProperties;
import bank.alert.fixture.SyntheticFixtureLoader;
import bank.alert.fixture.SyntheticHoldingFixture;
import bank.alert.ingestion.SearchUniverseProvider;
import bank.alert.operations.OperationalMetrics;
import bank.alert.domain.HoldingSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ConditionalOnProperty(name = "alert.holdings.mode", havingValue = "fixture", matchIfMissing = true)
@Component
public class SyntheticHoldingsSearchUniverseProvider implements SearchUniverseProvider {

    private static final int SHARE_SCALE = 8;

    private final SyntheticFixtureLoader fixtures;
    private final IngestionProperties properties;
    private final OperationalMetrics metrics;

    public SyntheticHoldingsSearchUniverseProvider(
            SyntheticFixtureLoader fixtures, IngestionProperties properties,
            OperationalMetrics metrics) {
        this.fixtures = fixtures;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public List<String> eligibleInstrumentIds() {
        SyntheticHoldingFixture fixture = fixtures.loadDefault();
        List<SyntheticHoldingFixture.Position> heldPositions = fixture.positions().stream()
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

    private static HoldingSnapshot snapshot(SyntheticHoldingFixture.Position position) {
        return new HoldingSnapshot(position.snapshotId(), position.clientRef(), position.accountRef(),
                position.assignedAgentSubject(), position.instrumentId(), position.quantity(),
                position.unitPrice(), position.valuationCurrency(), position.fxToSgd(),
                position.valueSgd(), position.valuedAt(), position.freshness(), position.sourceVersion());
    }
}
