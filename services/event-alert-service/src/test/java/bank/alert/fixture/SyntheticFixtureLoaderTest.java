package bank.alert.fixture;

import bank.alert.domain.ValuationFreshness;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticFixtureLoaderTest {

    private final SyntheticFixtureLoader loader = new SyntheticFixtureLoader(
            new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void loadsDeterministicSyntheticHoldings() {
        SyntheticHoldingFixture fixture = loader.loadDefault();

        assertThat(fixture.contractVersion()).isEqualTo(1);
        assertThat(fixture.positions()).hasSize(95);
        assertThat(fixture.unavailable()).isEmpty();
        assertThat(loader.toSnapshots(fixture))
                .extracting(snapshot -> snapshot.getClientRef())
                .allMatch(value -> value.startsWith("syn-client-"));
        assertThat(fixture.positions()).extracting(SyntheticHoldingFixture.Position::snapshotId)
                .doesNotHaveDuplicates();
        assertThat(fixture.positions().stream().map(SyntheticHoldingFixture.Position::clientRef).distinct())
                .hasSize(20);
        assertThat(fixture.positions().stream()
                .filter(position -> !position.instrumentId().equals("SYN-SGX-001"))
                .collect(Collectors.groupingBy(SyntheticHoldingFixture.Position::clientRef,
                        Collectors.counting())).values())
                .allMatch(count -> count >= 4 && count <= 6);
        assertThat(fixture.positions().stream()
                .collect(Collectors.groupingBy(SyntheticHoldingFixture.Position::clientRef,
                        Collectors.reducing(BigDecimal.ZERO,
                                SyntheticHoldingFixture.Position::valueSgd, BigDecimal::add))).values())
                .allMatch(total -> total.compareTo(new BigDecimal("100000.0000")) == 0);
        assertThat(fixture.positions()).extracting(SyntheticHoldingFixture.Position::valuationCurrency)
                .contains("SGD", "USD", "GBP", "HKD");
        assertThat(fixture.positions().get(1).freshness()).isEqualTo(ValuationFreshness.STALE);
    }
}
