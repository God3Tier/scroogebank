package bank.alert.ingestion;

import bank.alert.config.AlertMode;
import bank.alert.config.AlertModeProperties;
import bank.alert.config.IngestionProperties;
import bank.alert.config.SearchFilterProperties;
import bank.alert.domain.Instrument;
import bank.alert.domain.SearchWebsite;
import bank.alert.repository.InstrumentRepository;
import bank.alert.repository.SearchWebsiteRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class IngestionJob {

    private final AlertModeProperties mode;
    private final IngestionProperties properties;
    private final SearchFilterProperties filter;
    private final SearchUniverseProvider searchUniverse;
    private final InstrumentRepository instruments;
    private final SearchWebsiteRepository websites;
    private final IngestionCoordinator coordinator;
    private final InstrumentCoverageService coverage;

    public IngestionJob(AlertModeProperties mode, IngestionProperties properties, SearchFilterProperties filter,
            SearchUniverseProvider searchUniverse, InstrumentRepository instruments,
            SearchWebsiteRepository websites, IngestionCoordinator coordinator, InstrumentCoverageService coverage) {
        this.mode = mode;
        this.properties = properties;
        this.filter = filter;
        this.searchUniverse = searchUniverse;
        this.instruments = instruments;
        this.websites = websites;
        this.coordinator = coordinator;
        this.coverage = coverage;
    }

    /** Entry point for one bounded ECS worker invocation. Scheduling remains an IaC responsibility. */
    public IngestionOutcome runScheduledWindow(Instant windowTo) {
        requireWorkerMode();
        List<CatalogueEntry> catalogue = catalogue();
        if (!filterAllows(catalogue)) return IngestionOutcome.skipped(IngestionStatus.INVALID_FILTER, windowTo);
        catalogue = restrict(catalogue);
        InstrumentCoverageService.CoverageBatch batch = coverage.scheduled(
                catalogue.stream().map(CatalogueEntry::id).toList(), windowTo);
        if (batch.instrumentIds().isEmpty()) {
            return IngestionOutcome.skipped(IngestionStatus.NO_QUERIES, windowTo);
        }
        IngestionOutcome outcome = coordinator.run(batch.windowFrom(), batch.windowTo(), terms(catalogue, batch.instrumentIds()));
        if (outcome.status() == IngestionStatus.SUCCEEDED
                || outcome.status() == IngestionStatus.ALREADY_SUCCEEDED) {
            coverage.advance(batch.instrumentIds(), windowTo);
        }
        return outcome;
    }

    /** Internal bounded replay entry point. Authentication/audit belongs to the future operator adapter. */
    public IngestionOutcome replayWindow(Instant windowFrom, Instant windowTo) {
        requireWorkerMode();
        List<CatalogueEntry> catalogue = catalogue();
        if (!filterAllows(catalogue)) return IngestionOutcome.skipped(IngestionStatus.INVALID_FILTER, windowTo);
        catalogue = restrict(catalogue);
        List<String> selected = coverage.replay(catalogue.stream().map(CatalogueEntry::id).toList(), windowTo);
        return coordinator.replay(windowFrom, windowTo, terms(catalogue, selected));
    }

    /**
     * Searches these companies over a recent window (ADR-0081). With a {@code domain}, which must be one of the
     * added websites, only that website is searched; without one, every website is: each company's official
     * sites, the shared news sites and all the added websites. The companies need not be in the scheduled selection.
     */
    public IngestionOutcome researchCompanies(List<String> instrumentIds, String domain, Instant windowFrom, Instant windowTo) {
        requireWorkerMode();
        List<String> added = addedWebsites();
        if (domain != null && !added.contains(domain)) return IngestionOutcome.skipped(IngestionStatus.NO_QUERIES, windowTo);
        List<InstrumentSearchTerms> terms = instruments.findAllById(instrumentIds).stream()
                .filter(Instrument::isActive)
                .sorted(Comparator.comparing(Instrument::getId))
                .map(instrument -> new InstrumentSearchTerms(instrument.getTicker(), instrument.getIssuerName(),
                        instrument.getSector(), instrument.getSearchAliases(), properties.eventTerms(),
                        domain == null ? instrument.getOfficialDomains() : List.of(),
                        domain == null ? added : List.of(domain)))
                .toList();
        if (terms.isEmpty()) return IngestionOutcome.skipped(IngestionStatus.NO_QUERIES, windowTo);
        return coordinator.research(windowFrom, windowTo, terms, domain == null ? properties.approvedDomains() : List.of());
    }

    private List<CatalogueEntry> catalogue() {
        Set<String> eligibleIds = Set.copyOf(searchUniverse.eligibleInstrumentIds());
        if (eligibleIds.isEmpty()) return List.of();
        List<String> added = addedWebsites();
        return instruments.findAllById(eligibleIds).stream()
                .filter(instrument -> instrument.isActive() && eligibleIds.contains(instrument.getId()))
                .sorted(Comparator.comparing(instrument -> instrument.getId()))
                .map(instrument -> new CatalogueEntry(instrument.getId(), new InstrumentSearchTerms(
                        instrument.getTicker(), instrument.getIssuerName(), instrument.getSector(),
                        instrument.getSearchAliases(), properties.eventTerms(), instrument.getOfficialDomains(), added)))
                .toList();
    }

    /** The websites agents added; they are searched for every company (ADR-0082). */
    private List<String> addedWebsites() {
        return websites.findByActiveTrueOrderByAddedAtAsc().stream().map(SearchWebsite::getDomain).toList();
    }

    /** With ALERT_SEARCH_ONLY_INSTRUMENT_ID set, the instrument must be eligible, or nothing is searched or reserved. */
    private boolean filterAllows(List<CatalogueEntry> catalogue) {
        return !filter.restricted() || catalogue.stream().anyMatch(entry -> entry.id().equals(filter.onlyInstrumentId()));
    }

    private List<CatalogueEntry> restrict(List<CatalogueEntry> catalogue) {
        if (!filter.restricted()) return catalogue;
        return catalogue.stream().filter(entry -> entry.id().equals(filter.onlyInstrumentId())).toList();
    }

    private static List<InstrumentSearchTerms> terms(List<CatalogueEntry> catalogue, List<String> selected) {
        Set<String> ids = Set.copyOf(selected);
        return catalogue.stream().filter(entry -> ids.contains(entry.id())).map(CatalogueEntry::terms).toList();
    }

    private record CatalogueEntry(String id, InstrumentSearchTerms terms) {}

    private void requireWorkerMode() {
        if (mode.mode() != AlertMode.WORKER) {
            throw new IllegalStateException("ingestion jobs may run only in WORKER mode");
        }
    }
}
