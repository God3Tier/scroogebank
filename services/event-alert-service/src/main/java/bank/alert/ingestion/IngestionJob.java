package bank.alert.ingestion;

import bank.alert.config.AlertMode;
import bank.alert.config.AlertModeProperties;
import bank.alert.config.IngestionProperties;
import bank.alert.repository.InstrumentRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class IngestionJob {

    private final AlertModeProperties mode;
    private final IngestionProperties properties;
    private final SearchUniverseProvider searchUniverse;
    private final InstrumentRepository instruments;
    private final IngestionCoordinator coordinator;

    public IngestionJob(AlertModeProperties mode, IngestionProperties properties,
            SearchUniverseProvider searchUniverse, InstrumentRepository instruments,
            IngestionCoordinator coordinator) {
        this.mode = mode;
        this.properties = properties;
        this.searchUniverse = searchUniverse;
        this.instruments = instruments;
        this.coordinator = coordinator;
    }

    /** Entry point for one bounded ECS worker invocation. Scheduling remains an IaC responsibility. */
    public IngestionOutcome runScheduledWindow(Instant windowTo) {
        requireWorkerMode();
        return coordinator.run(windowTo, catalogue());
    }

    /** Internal bounded replay entry point. Authentication/audit belongs to the future operator adapter. */
    public IngestionOutcome replayWindow(Instant windowFrom, Instant windowTo) {
        requireWorkerMode();
        return coordinator.replay(windowFrom, windowTo, catalogue());
    }

    private List<InstrumentSearchTerms> catalogue() {
        Set<String> eligibleIds = Set.copyOf(searchUniverse.eligibleInstrumentIds());
        if (eligibleIds.isEmpty()) return List.of();
        return instruments.findAllById(eligibleIds).stream()
                .filter(instrument -> instrument.isActive() && eligibleIds.contains(instrument.getId()))
                .sorted(Comparator.comparing(instrument -> instrument.getId()))
                .map(instrument -> new InstrumentSearchTerms(
                        instrument.getTicker(), instrument.getIssuerName(), instrument.getSector(),
                        List.of(), properties.eventTerms()))
                .toList();
    }

    private void requireWorkerMode() {
        if (mode.mode() != AlertMode.WORKER) {
            throw new IllegalStateException("ingestion jobs may run only in WORKER mode");
        }
    }
}
