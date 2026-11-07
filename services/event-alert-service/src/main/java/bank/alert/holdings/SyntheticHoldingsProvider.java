package bank.alert.holdings;

import bank.alert.domain.HoldingSnapshot;
import bank.alert.domain.ValuationFreshness;
import bank.alert.fixture.SyntheticFixtureLoader;
import bank.alert.fixture.SyntheticHoldingFixture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ConditionalOnProperty(name = "alert.holdings.mode", havingValue = "fixture", matchIfMissing = true)
@Component
public class SyntheticHoldingsProvider implements HoldingsProvider {

    private final SyntheticFixtureLoader fixtureLoader;
    private final ValuationFreshnessPolicy freshnessPolicy;

    public SyntheticHoldingsProvider(
            SyntheticFixtureLoader fixtureLoader,
            ValuationFreshnessPolicy freshnessPolicy) {
        this.fixtureLoader = fixtureLoader;
        this.freshnessPolicy = freshnessPolicy;
    }

    @Override
    public HoldingsReadResult read(HoldingsReadRequest request) {
        SyntheticHoldingFixture fixture = fixtureLoader.loadDefault();
        List<HoldingSnapshot> positions = new ArrayList<>();
        List<UnavailableHolding> unavailable = new ArrayList<>();
        Set<String> matchedInstrumentIds = new HashSet<>();

        fixture.positions().stream()
                .filter(position -> request.instrumentIds().contains(position.instrumentId()))
                .filter(position -> request.assignedAgentSubject().equals(position.assignedAgentSubject()))
                .forEach(position -> {
                    matchedInstrumentIds.add(position.instrumentId());
                    ValuationFreshness freshness = freshnessPolicy.classify(
                            position.valuedAt(), request.asOf());
                    if (freshness == ValuationFreshness.EXPIRED) {
                        unavailable.add(new UnavailableHolding(
                                position.clientRef(), position.instrumentId(),
                                HoldingUnavailableReason.EXPIRED));
                    } else {
                        positions.add(toSnapshot(position, freshness));
                    }
                });

        request.instrumentIds().stream()
                .filter(instrumentId -> !matchedInstrumentIds.contains(instrumentId))
                .sorted()
                .map(instrumentId -> new UnavailableHolding(
                        null, instrumentId, HoldingUnavailableReason.MISSING))
                .forEach(unavailable::add);

        return new HoldingsReadResult(1, request.asOf(), positions, unavailable);
    }

    @Override
    public List<String> assignedAgentsHolding(Set<String> instrumentIds) {
        return fixtureLoader.loadDefault().positions().stream()
                .filter(position -> position.quantity().signum() > 0)
                .filter(position -> instrumentIds.contains(position.instrumentId()))
                .map(SyntheticHoldingFixture.Position::assignedAgentSubject)
                .distinct()
                .sorted()
                .toList();
    }

    private static HoldingSnapshot toSnapshot(
            SyntheticHoldingFixture.Position position,
            ValuationFreshness freshness) {
        return new HoldingSnapshot(
                position.snapshotId(), position.clientRef(), position.accountRef(),
                position.assignedAgentSubject(), position.instrumentId(),
                position.quantity(), position.unitPrice(), position.valuationCurrency(),
                position.fxToSgd(), position.valueSgd(), position.valuedAt(), freshness,
                position.sourceVersion());
    }
}
