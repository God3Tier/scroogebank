package bank.alert.holdings;

import bank.alert.domain.HoldingSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Holdings from the maintained, CRM-linked portfolios (ADR-0066). Unlike the fixture
 * provider, this returns the <em>complete</em> portfolio of every account holding an
 * affected instrument, including expired positions: the exposure calculator then
 * refuses to score rather than computing a percentage from a shrunken total.
 */
@Component
@ConditionalOnProperty(name = "alert.holdings.mode", havingValue = "database")
public class DatabaseHoldingsProvider implements HoldingsProvider {

    private final CurrentHoldingsReader reader;

    public DatabaseHoldingsProvider(CurrentHoldingsReader reader) {
        this.reader = reader;
    }

    @Override
    public HoldingsReadResult read(HoldingsReadRequest request) {
        List<HoldingSnapshot> positions = new ArrayList<>();
        Set<String> matched = new HashSet<>();
        for (CurrentHoldingsReader.AccountPortfolio portfolio : reader.read(
                request.asOf(), request.instrumentIds(), request.assignedAgentSubject())) {
            positions.addAll(portfolio.positions());
            portfolio.positions().forEach(position -> matched.add(position.getInstrumentId()));
        }
        List<UnavailableHolding> unavailable = request.instrumentIds().stream()
                .filter(id -> !matched.contains(id)).sorted()
                .map(id -> new UnavailableHolding(null, id, HoldingUnavailableReason.MISSING))
                .toList();
        return new HoldingsReadResult(2, request.asOf(), positions, unavailable);
    }

    @Override
    public List<String> assignedAgentsHolding(Set<String> instrumentIds) {
        return reader.read(java.time.Instant.now(), instrumentIds, null).stream()
                .map(CurrentHoldingsReader.AccountPortfolio::agentSubject)
                .distinct().sorted().toList();
    }
}
