package bank.alert.holdings;

import bank.alert.config.IngestionProperties;
import bank.alert.domain.ValuationFreshness;
import bank.alert.fixture.SyntheticFixtureLoader;
import bank.alert.fixture.SyntheticHoldingFixture;
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
    void selectsOnlyInstrumentsMeetingClientCountAndShareWithoutReturningPrivateFacts() {
        SyntheticFixtureLoader fixtures = mock(SyntheticFixtureLoader.class);
        when(fixtures.loadDefault()).thenReturn(new SyntheticHoldingFixture(1,
                Instant.parse("2026-09-30T00:00:00Z"), List.of(
                        position("client-1", "ELIGIBLE", "100.00"),
                        position("client-2", "ELIGIBLE", "50.00"),
                        position("client-3", "TOO-FEW-CLIENTS", "25.00"),
                        position("client-4", "ZERO-VALUE", "0.00")), List.of()));
        SyntheticHoldingsSearchUniverseProvider provider =
                new SyntheticHoldingsSearchUniverseProvider(fixtures, properties());

        assertThat(provider.eligibleInstrumentIds()).containsExactly("ELIGIBLE");
    }

    private static SyntheticHoldingFixture.Position position(
            String clientRef, String instrumentId, String valueSgd) {
        return new SyntheticHoldingFixture.Position(UUID.randomUUID(), clientRef,
                "account-" + clientRef, "agent-1", instrumentId,
                BigDecimal.ONE, new BigDecimal(valueSgd), "SGD", BigDecimal.ONE,
                new BigDecimal(valueSgd), Instant.parse("2026-09-30T00:00:00Z"),
                ValuationFreshness.FRESH, "test-v1");
    }

    private static IngestionProperties properties() {
        return new IngestionProperties(false, "agentcore-web-search-1.2.0",
                Duration.ofMinutes(5), Duration.ofHours(4), 4, 10, 3,
                Duration.ofDays(90), new BigDecimal("0.40"), 2,
                List.of("sgx.com"), List.of("earnings"));
    }
}
