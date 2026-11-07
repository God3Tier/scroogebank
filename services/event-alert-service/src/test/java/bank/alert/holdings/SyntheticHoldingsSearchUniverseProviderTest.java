package bank.alert.holdings;

import bank.alert.config.IngestionProperties;
import bank.alert.domain.ValuationFreshness;
import bank.alert.fixture.SyntheticFixtureLoader;
import bank.alert.fixture.SyntheticHoldingFixture;
import bank.alert.operations.OperationalMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyntheticHoldingsSearchUniverseProviderTest {

    @Test
    void selectsExpectedGlobalFixtureUniverse() {
        OperationalMetrics metrics = mock(OperationalMetrics.class);
        SyntheticHoldingsSearchUniverseProvider provider = new SyntheticHoldingsSearchUniverseProvider(
                new SyntheticFixtureLoader(new ObjectMapper().findAndRegisterModules()), properties(), metrics);

        assertThat(provider.eligibleInstrumentIds()).containsExactly(
                "HKEX-0700", "LSE-SHEL", "NASDAQ-AAPL", "NASDAQ-MSFT",
                "NYSE-JPM", "NYSE-V", "SGX-C6L", "SGX-D05", "SGX-O39", "SGX-Z74");
        verify(metrics).searchUniverse(10, 10);
    }

    @Test
    void appliesThresholdsAcrossOneHundredClients() {
        SyntheticFixtureLoader fixtures = mock(SyntheticFixtureLoader.class);
        OperationalMetrics metrics = mock(OperationalMetrics.class);
        List<SyntheticHoldingFixture.Position> positions = new java.util.ArrayList<>();
        for (int client = 1; client <= 100; client++) {
            positions.add(position("client-" + client, "account-" + client, "BASE", "100.00"));
        }
        for (int client = 1; client <= 20; client++) {
            positions.add(position("client-" + client, "account-" + client, "COMMON-20", "1.00"));
        }
        for (int client = 1; client <= 19; client++) {
            positions.add(position("client-" + client, "account-" + client, "BELOW-20", "1.00"));
        }
        positions.add(position("client-99", "account-99", "BELOW-50", "99.00"));
        positions.add(position("client-100", "account-100", "AT-50", "100.00"));
        when(fixtures.loadDefault()).thenReturn(new SyntheticHoldingFixture(1,
                Instant.parse("2026-09-30T00:00:00Z"), positions, List.of()));
        SyntheticHoldingsSearchUniverseProvider provider =
                new SyntheticHoldingsSearchUniverseProvider(fixtures, properties(),
                        metrics);

        assertThat(provider.eligibleInstrumentIds()).containsExactly("BASE", "COMMON-20");
        verify(metrics).searchUniverse(2, 2);
    }

    @Test
    void derivesNewMultiMarketInstrumentsFromThirtyDifferentUsers() {
        SyntheticFixtureLoader fixtures = mock(SyntheticFixtureLoader.class);
        OperationalMetrics metrics = mock(OperationalMetrics.class);
        List<SyntheticHoldingFixture.Position> positions = new java.util.ArrayList<>();
        for (int client = 1; client <= 30; client++) {
            positions.add(position("new-client-" + client, "new-account-" + client,
                    "TSE-7203", "100.00"));
        }
        for (int client = 1; client <= 6; client++) {
            positions.add(position("new-client-" + client, "new-account-" + client,
                    "ASX-BHP", "10.00"));
        }
        for (int client = 1; client <= 5; client++) {
            positions.add(position("new-client-" + client, "new-account-" + client,
                    "XETRA-SAP", "10.00"));
        }
        positions.add(position("new-client-30", "concentrated-account", "NYSE-TSM", "60.00"));
        positions.add(position("new-client-30", "concentrated-account", "TSE-7203", "40.00"));
        when(fixtures.loadDefault()).thenReturn(new SyntheticHoldingFixture(1,
                Instant.parse("2026-10-29T00:00:00Z"), positions, List.of()));

        SyntheticHoldingsSearchUniverseProvider provider =
                new SyntheticHoldingsSearchUniverseProvider(fixtures, properties(), metrics);

        assertThat(provider.eligibleInstrumentIds())
                .containsExactly("ASX-BHP", "TSE-7203")
                .doesNotContain("XETRA-SAP", "NYSE-TSM");
        verify(metrics).searchUniverse(2, 2);
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
                Duration.ofDays(90), new BigDecimal("0.20"), 2,
                List.of("sgx.com"), List.of("earnings"));
    }
}
