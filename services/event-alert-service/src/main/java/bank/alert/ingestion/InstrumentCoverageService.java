package bank.alert.ingestion;

import bank.alert.config.IngestionProperties;
import bank.alert.domain.InstrumentSearchCoverage;
import bank.alert.repository.InstrumentSearchCoverageRepository;
import bank.alert.operations.OperationalMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class InstrumentCoverageService {

    private final InstrumentSearchCoverageRepository repository;
    private final IngestionProperties properties;
    private final Clock clock;
    private final OperationalMetrics metrics;

    public InstrumentCoverageService(InstrumentSearchCoverageRepository repository,
            IngestionProperties properties, Clock clock, OperationalMetrics metrics) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    public CoverageBatch scheduled(List<String> sortedIds, Instant windowTo) {
        List<String> ids = select(sortedIds, windowTo);
        Map<String, InstrumentSearchCoverage> coverage = repository.findAllById(ids).stream()
                .collect(Collectors.toMap(InstrumentSearchCoverage::getInstrumentId, Function.identity()));
        Instant oldest = ids.stream().map(id -> coverage.containsKey(id)
                        ? coverage.get(id).getLastSuccessfulTo()
                        : windowTo.minus(properties.initialLookback())).min(Instant::compareTo)
                .orElseGet(() -> windowTo.minus(properties.initialLookback()));
        metrics.searchBatch(ids.size(), sortedIds.size() - ids.size(),
                Math.max(0, Duration.between(oldest, windowTo).toSeconds()));
        if (!ids.isEmpty() && ids.stream().allMatch(id -> coverage.containsKey(id)
                && !coverage.get(id).getLastSuccessfulTo().isBefore(windowTo))) {
            return new CoverageBatch(List.of(), windowTo, windowTo);
        }
        Instant from = ids.stream().map(id -> coverage.containsKey(id)
                        ? coverage.get(id).getLastSuccessfulTo().minus(properties.overlap())
                        : windowTo.minus(properties.initialLookback())).min(Instant::compareTo)
                .orElse(windowTo.minus(properties.initialLookback()));
        return new CoverageBatch(ids, from, windowTo);
    }

    public List<String> replay(List<String> sortedIds, Instant windowTo) {
        return select(sortedIds, windowTo);
    }

    @Transactional
    public void advance(List<String> ids, Instant windowTo) {
        Map<String, InstrumentSearchCoverage> existing = repository.findAllById(ids).stream()
                .collect(Collectors.toMap(InstrumentSearchCoverage::getInstrumentId, Function.identity()));
        Instant now = clock.instant();
        repository.saveAll(ids.stream().map(id -> {
            InstrumentSearchCoverage row = existing.get(id);
            if (row == null) return new InstrumentSearchCoverage(id, windowTo, now);
            row.advanceTo(windowTo, now);
            return row;
        }).toList());
    }

    private List<String> select(List<String> sortedIds, Instant windowTo) {
        if (sortedIds.isEmpty()) return List.of();
        // Every eligible instrument is searched on every run; there is no rotation (ADR-0084).
        return List.copyOf(sortedIds);
    }

    public record CoverageBatch(List<String> instrumentIds, Instant windowFrom, Instant windowTo) {}
}
