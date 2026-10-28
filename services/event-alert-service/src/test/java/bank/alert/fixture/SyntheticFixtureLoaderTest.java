package bank.alert.fixture;

import bank.alert.domain.ValuationFreshness;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticFixtureLoaderTest {

    private final SyntheticFixtureLoader loader = new SyntheticFixtureLoader(
            new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void loadsDeterministicSyntheticHoldings() {
        SyntheticHoldingFixture fixture = loader.loadDefault();

        assertThat(fixture.contractVersion()).isEqualTo(1);
        assertThat(fixture.positions()).hasSize(4);
        assertThat(fixture.unavailable()).isEmpty();
        assertThat(loader.toSnapshots(fixture))
                .extracting(snapshot -> snapshot.getFreshness())
                .containsExactly(ValuationFreshness.FRESH, ValuationFreshness.STALE,
                        ValuationFreshness.FRESH, ValuationFreshness.FRESH);
        assertThat(loader.toSnapshots(fixture))
                .extracting(snapshot -> snapshot.getClientRef())
                .allMatch(value -> value.startsWith("syn-client-"));
    }
}
