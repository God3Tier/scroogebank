package bank.alert.holdings;

import bank.alert.config.IngestionProperties;
import bank.alert.domain.ValuationFreshness;
import bank.alert.fixture.SyntheticFixtureLoader;
import bank.alert.fixture.SyntheticHoldingFixture;
import bank.alert.scoring.ExposureCalculator;
import bank.alert.operations.OperationalMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyntheticHoldingsSearchUniverseProviderTest {

    @Test
    void appliesInclusiveCommonalityAndPerAccountConcentrationRules() {
        SyntheticFixtureLoader fixtures = mock(SyntheticFixtureLoader.class);
        List<SyntheticHoldingFixture.Position> positions = new java.util.ArrayList<>();
        for (int client = 1; client <= 10; client++) {
            positions.add(position("client-" + client, "account-" + client, "BASE", client == 3 ? "51.00" : "90.00"));
        }
        positions.add(position("client-1", "account-1", "COMMON-20", "10.00"));
        positions.add(position("client-2", "account-2", "COMMON-20", "10.00"));
        positions.add(position("client-3", "account-3", "BELOW-50", "49.00"));
        positions.add(position("client-4", "account-4", "AT-50", "90.00"));
        when(fixtures.loadDefault()).thenReturn(new SyntheticHoldingFixture(1,
                Instant.parse("2026-09-30T00:00:00Z"), positions, List.of()));
        SyntheticHoldingsSearchUniverseProvider provider =
                new SyntheticHoldingsSearchUniverseProvider(fixtures, properties(), new ExposureCalculator(),
                        mock(OperationalMetrics.class));

        assertThat(provider.eligibleInstrumentIds()).containsExactly("AT-50", "BASE", "COMMON-20");
    }

    private static SyntheticHoldingFixture.Position position(
            String clientRef, String accountRef, String instrumentId, String valueSgd) {
        return new SyntheticHoldingFixture.Position(UUID.randomUUID(), clientRef,
                accountRef, "agent-1", instrumentId,
                BigDecimal.ONE, new BigDecimal(valueSgd), "SGD", BigDecimal.ONE,
                new BigDecimal(valueSgd), Instant.parse("2026-09-30T00:00:00Z"),
                ValuationFreshness.FRESH, "test-v1");
    }

    private static IngestionProperties properties() {
        return new IngestionProperties(false, "tavily",
                Duration.ofMinutes(5), Duration.ofHours(4), 4, 10, 3,
                Duration.ofDays(90), new BigDecimal("0.20"), 2, new BigDecimal("0.50"),
                List.of("sgx.com"), List.of("earnings"));
    }
}
