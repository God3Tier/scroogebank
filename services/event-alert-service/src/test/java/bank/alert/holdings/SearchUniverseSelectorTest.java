package bank.alert.holdings;

import bank.alert.config.IngestionProperties;
import bank.alert.domain.HoldingSnapshot;
import bank.alert.domain.ValuationFreshness;
import bank.alert.operations.OperationalMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The thresholds the maintained portfolios drive (plan section 7, acceptance items 2 and 3). */
class SearchUniverseSelectorTest {

    private static final Instant VALUED = Instant.parse("2026-10-30T00:00:00Z");

    @Test
    void thirtyClientsAcrossAgentsAndAccountsAreCountedByDistinctClient() {
        OperationalMetrics metrics = mock(OperationalMetrics.class);
        SearchUniverseSelector selector = new SearchUniverseSelector(properties(), metrics);
        List<HoldingSnapshot> positions = new ArrayList<>();
        for (int client = 1; client <= 30; client++) {
            String agent = "agent-" + (client % 3);
            positions.add(position(client, "a" + client, agent, "BASE", "1000.00"));
        }
        // Six clients hold COMMON, two of them in two accounts: still six clients, 20% of 30 -> included.
        for (int client = 1; client <= 6; client++) {
            positions.add(position(client, "a" + client, "agent-" + (client % 3), "COMMON", "1.00"));
        }
        addSecondAccount(positions, 1, "a1-second", "agent-1", "COMMON");
        addSecondAccount(positions, 2, "a2-second", "agent-2", "COMMON");
        // Five clients, two of them in two accounts: seven accounts but five clients, 16.7% -> excluded.
        for (int client = 11; client <= 15; client++) {
            positions.add(position(client, "a" + client, "agent-" + (client % 3), "BELOW", "1.00"));
        }
        addSecondAccount(positions, 11, "a11-second", "agent-2", "BELOW");
        addSecondAccount(positions, 12, "a12-second", "agent-0", "BELOW");
        // One client puts half of one account in CONCENTRATED. One client is not enough: the account share no longer qualifies it.
        positions.add(position(20, "a20", "agent-2", "CONCENTRATED", "1000.00"));

        List<String> universe = selector.select(positions);

        assertThat(universe).containsExactly("BASE", "COMMON");
        verify(metrics).searchUniverse(2, 2);
    }

    @Test
    void aCompanyOneClientHoldsIsNotSearchedHoweverLargeTheHolding() {
        SearchUniverseSelector selector = new SearchUniverseSelector(properties(), mock(OperationalMetrics.class));
        // 90 of 100 is 90% of the account, but only one client holds it, so it is not searched.
        List<HoldingSnapshot> oneClient = List.of(
                position(1, "a1", "agent-1", "SMALL", "10.00"), position(1, "a1", "agent-1", "LARGE", "90.00"),
                position(2, "a2", "agent-1", "OTHER", "100.00"));

        assertThat(selector.select(oneClient)).isEmpty();
    }

    /** A second account of the same client: a small position next to a large filler, so it is not itself concentrated. */
    private static void addSecondAccount(List<HoldingSnapshot> positions, int client, String account, String agent, String instrument) {
        positions.add(position(client, account, agent, instrument, "1.00"));
        positions.add(position(client, account, agent, "BASE", "1000.00"));
    }

    private static HoldingSnapshot position(int client, String account, String agent, String instrument, String value) {
        return new HoldingSnapshot(UUID.randomUUID(), "client-" + client, account, agent, instrument,
                BigDecimal.ONE, new BigDecimal(value), "SGD", BigDecimal.ONE, new BigDecimal(value), VALUED,
                ValuationFreshness.FRESH, "test");
    }

    private static IngestionProperties properties() {
        return new IngestionProperties(false, "tavily", Duration.ofMinutes(5), Duration.ofHours(4), 4, 10, 3,
                Duration.ofDays(90), new BigDecimal("0.20"), 2,
                List.of("sgx.com"), List.of("earnings"));
    }
}
