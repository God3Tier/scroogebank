package bank.alert.holdings;

import bank.alert.domain.ValuationFreshness;
import bank.alert.fixture.SyntheticFixtureLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticHoldingsProviderTest {

    private final SyntheticHoldingsProvider provider = new SyntheticHoldingsProvider(
            new SyntheticFixtureLoader(new ObjectMapper().registerModule(new JavaTimeModule())),
            new ValuationFreshnessPolicy());

    @Test
    void returnsMatchingPositionsAndExplicitMissingInstrument() {
        HoldingsReadResult result = provider.read(new HoldingsReadRequest(
                Set.of("SYN-SGX-001", "SYN-MISSING-001"),
                Instant.parse("2026-09-27T00:00:00Z"), "syn-agent-001"));

        assertThat(result.contractVersion()).isEqualTo(1);
        assertThat(result.positions()).hasSize(1)
                .extracting(position -> position.getFreshness())
                .containsExactly(ValuationFreshness.FRESH);
        assertThat(result.unavailable()).containsExactly(new UnavailableHolding(
                null, "SYN-MISSING-001", HoldingUnavailableReason.MISSING));
    }

    @Test
    void returnsExpiredPositionsAsUnavailableInsteadOfZero() {
        HoldingsReadResult result = provider.read(new HoldingsReadRequest(
                Set.of("SYN-SGX-001"), Instant.parse("2026-10-01T00:00:01Z"),
                "syn-agent-001"));

        assertThat(result.positions()).isEmpty();
        assertThat(result.unavailable()).hasSize(1)
                .allMatch(item -> item.reason() == HoldingUnavailableReason.EXPIRED);
    }

    @Test
    void neverReturnsAnotherAgentsHoldings() {
        HoldingsReadResult result = provider.read(new HoldingsReadRequest(
                Set.of("SYN-SGX-001"), Instant.parse("2026-09-27T00:00:00Z"),
                "syn-agent-002"));

        assertThat(result.positions()).hasSize(1)
                .allMatch(position -> position.getAssignedAgentSubject().equals("syn-agent-002"));
        assertThat(result.positions().getFirst().getFreshness())
                .isEqualTo(ValuationFreshness.STALE);
    }
}
