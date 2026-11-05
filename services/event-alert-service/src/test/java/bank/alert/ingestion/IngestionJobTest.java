package bank.alert.ingestion;

import bank.alert.config.AlertMode;
import bank.alert.config.AlertModeProperties;
import bank.alert.config.IngestionProperties;
import bank.alert.config.SearchFilterProperties;
import bank.alert.domain.Instrument;
import bank.alert.domain.InstrumentSourceDomain;
import bank.alert.repository.InstrumentRepository;
import bank.alert.repository.InstrumentSourceDomainRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionJobTest {

    @Test
    void refusesToRunInsideTheApiProcess() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        InstrumentSourceDomainRepository sourceDomains = mock(InstrumentSourceDomainRepository.class);
        SearchUniverseProvider searchUniverse = mock(SearchUniverseProvider.class);
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        InstrumentCoverageService coverage = mock(InstrumentCoverageService.class);
        IngestionJob job = new IngestionJob(new AlertModeProperties(AlertMode.API), properties(),
                new SearchFilterProperties(null), searchUniverse, instruments, sourceDomains, coordinator, coverage);

        assertThatThrownBy(() -> job.runScheduledWindow(Instant.parse("2026-09-28T04:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORKER mode");
        verify(searchUniverse, never()).eligibleInstrumentIds();
        verify(coordinator, never()).run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void searchesNewlyRegisteredInstrumentAndSkipsUnknownOrInactiveHoldings() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        InstrumentSourceDomainRepository sourceDomains = mock(InstrumentSourceDomainRepository.class);
        SearchUniverseProvider searchUniverse = mock(SearchUniverseProvider.class);
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        InstrumentCoverageService coverage = mock(InstrumentCoverageService.class);
        IngestionJob job = new IngestionJob(new AlertModeProperties(AlertMode.WORKER), properties(),
                new SearchFilterProperties(null), searchUniverse, instruments, sourceDomains, coordinator, coverage);
        Instant from = Instant.parse("2026-09-28T02:00:00Z");
        Instant to = Instant.parse("2026-09-28T04:00:00Z");
        Instrument instrument = mock(Instrument.class);
        when(instrument.getTicker()).thenReturn("7203");
        when(instrument.getIssuerName()).thenReturn("Toyota Motor Corporation");
        when(instrument.getSector()).thenReturn("Automotive");
        when(instrument.getSearchAliases()).thenReturn(List.of("Toyota"));
        when(instrument.getOfficialDomains()).thenReturn(List.of("global.toyota", "jpx.co.jp"));
        when(instrument.getId()).thenReturn("TSE-7203");
        when(instrument.isActive()).thenReturn(true);
        Instrument inactive = mock(Instrument.class);
        when(inactive.getId()).thenReturn("INACTIVE");
        when(inactive.isActive()).thenReturn(false);
        when(searchUniverse.eligibleInstrumentIds())
                .thenReturn(List.of("TSE-7203", "INACTIVE", "UNKNOWN"));
        when(instruments.findAllById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(inactive, instrument));
        when(coverage.replay(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(to)))
                .thenReturn(List.of("TSE-7203"));

        job.replayWindow(from, to);

        verify(coordinator).replay(org.mockito.ArgumentMatchers.eq(from),
                org.mockito.ArgumentMatchers.eq(to), org.mockito.ArgumentMatchers.argThat(terms ->
                        terms.size() == 1 && terms.getFirst().ticker().equals("7203")
                                && terms.getFirst().aliases().equals(List.of("Toyota"))
                                && terms.getFirst().officialDomains()
                                .equals(List.of("global.toyota", "jpx.co.jp"))));
    }

    @Test
    void passesActiveAgentAddedDomainsToTheSearch() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        InstrumentSourceDomainRepository sourceDomains = mock(InstrumentSourceDomainRepository.class);
        SearchUniverseProvider searchUniverse = mock(SearchUniverseProvider.class);
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        InstrumentCoverageService coverage = mock(InstrumentCoverageService.class);
        IngestionJob job = new IngestionJob(new AlertModeProperties(AlertMode.WORKER), properties(),
                new SearchFilterProperties(null), searchUniverse, instruments, sourceDomains, coordinator, coverage);
        Instant from = Instant.parse("2026-09-28T02:00:00Z");
        Instant to = Instant.parse("2026-09-28T04:00:00Z");
        Instrument instrument = mock(Instrument.class);
        when(instrument.getId()).thenReturn("SGX-D05");
        when(instrument.getTicker()).thenReturn("D05");
        when(instrument.getIssuerName()).thenReturn("DBS Group Holdings Ltd");
        when(instrument.getSector()).thenReturn("Financials");
        when(instrument.getSearchAliases()).thenReturn(List.of("DBS"));
        when(instrument.getOfficialDomains()).thenReturn(List.of("sgx.com"));
        when(instrument.isActive()).thenReturn(true);
        when(searchUniverse.eligibleInstrumentIds()).thenReturn(List.of("SGX-D05"));
        when(instruments.findAllById(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(instrument));
        when(sourceDomains.findByInstrumentIdInAndActiveTrueOrderByAddedAtAsc(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new InstrumentSourceDomain(java.util.UUID.randomUUID(), "SGX-D05",
                        "businesstimes.com.sg", "agent-a", from)));
        when(coverage.replay(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(to)))
                .thenReturn(List.of("SGX-D05"));

        job.replayWindow(from, to);

        verify(coordinator).replay(org.mockito.ArgumentMatchers.eq(from), org.mockito.ArgumentMatchers.eq(to),
                org.mockito.ArgumentMatchers.argThat(terms -> terms.size() == 1
                        && terms.getFirst().officialDomains().equals(List.of("sgx.com"))
                        && terms.getFirst().customDomains().equals(List.of("businesstimes.com.sg"))));
    }

    // ---- ALERT_SEARCH_ONLY_INSTRUMENT_ID (ADR-0084) ------------------------------------------------

    private static Instrument eligible(String id, String ticker) {
        Instrument instrument = mock(Instrument.class);
        when(instrument.getId()).thenReturn(id);
        when(instrument.getTicker()).thenReturn(ticker);
        when(instrument.getIssuerName()).thenReturn("Issuer " + ticker);
        when(instrument.getSector()).thenReturn("Sector");
        when(instrument.getSearchAliases()).thenReturn(List.of());
        when(instrument.getOfficialDomains()).thenReturn(List.of("example.com"));
        when(instrument.isActive()).thenReturn(true);
        return instrument;
    }

    private IngestionJob filteredJob(String onlyId, SearchUniverseProvider universe, InstrumentRepository instruments,
            IngestionCoordinator coordinator, InstrumentCoverageService coverage) {
        return new IngestionJob(new AlertModeProperties(AlertMode.WORKER), properties(),
                new SearchFilterProperties(onlyId), universe, instruments,
                mock(InstrumentSourceDomainRepository.class), coordinator, coverage);
    }

    @Test
    void anUnsetFilterSearchesEveryEligibleInstrument() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        SearchUniverseProvider universe = mock(SearchUniverseProvider.class);
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        InstrumentCoverageService coverage = mock(InstrumentCoverageService.class);
        Instant to = Instant.parse("2026-11-05T04:00:00Z");
        Instrument a = eligible("A", "AAA");
        Instrument b = eligible("B", "BBB");
        when(universe.eligibleInstrumentIds()).thenReturn(List.of("A", "B"));
        when(instruments.findAllById(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(a, b));
        when(coverage.scheduled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(to)))
                .thenReturn(new InstrumentCoverageService.CoverageBatch(List.of("A", "B"), to.minusSeconds(3600), to));
        when(coordinator.run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(IngestionOutcome.skipped(IngestionStatus.NO_QUERIES, to));

        filteredJob(null, universe, instruments, coordinator, coverage).runScheduledWindow(to);

        verify(coverage).scheduled(org.mockito.ArgumentMatchers.eq(List.of("A", "B")), org.mockito.ArgumentMatchers.eq(to));
    }

    @Test
    void aSetFilterSearchesOnlyThatEligibleInstrument() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        SearchUniverseProvider universe = mock(SearchUniverseProvider.class);
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        InstrumentCoverageService coverage = mock(InstrumentCoverageService.class);
        Instant to = Instant.parse("2026-11-05T04:00:00Z");
        Instrument a = eligible("A", "AAA");
        Instrument b = eligible("B", "BBB");
        when(universe.eligibleInstrumentIds()).thenReturn(List.of("A", "B"));
        when(instruments.findAllById(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(a, b));
        when(coverage.scheduled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(to)))
                .thenReturn(new InstrumentCoverageService.CoverageBatch(List.of("B"), to.minusSeconds(3600), to));
        when(coordinator.run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(IngestionOutcome.skipped(IngestionStatus.NO_QUERIES, to));

        filteredJob("B", universe, instruments, coordinator, coverage).runScheduledWindow(to);

        verify(coverage).scheduled(org.mockito.ArgumentMatchers.eq(List.of("B")), org.mockito.ArgumentMatchers.eq(to));
        verify(coordinator).run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(to),
                org.mockito.ArgumentMatchers.argThat(terms -> terms.size() == 1 && terms.getFirst().ticker().equals("BBB")));
    }

    @Test
    void aFilterNamingAnUnknownInactiveOrIneligibleInstrumentIsRefusedBeforeAnyQuotaIsUsed() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        SearchUniverseProvider universe = mock(SearchUniverseProvider.class);
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        InstrumentCoverageService coverage = mock(InstrumentCoverageService.class);
        Instrument inactive = mock(Instrument.class);
        when(inactive.getId()).thenReturn("OLD");
        when(inactive.isActive()).thenReturn(false);
        Instrument a = eligible("A", "AAA");
        // OLD is inactive, INELIGIBLE is not in the eligible set, and NOPE does not exist at all.
        when(universe.eligibleInstrumentIds()).thenReturn(List.of("A", "OLD"));
        when(instruments.findAllById(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(a, inactive));
        Instant to = Instant.parse("2026-11-05T04:00:00Z");

        for (String wanted : List.of("OLD", "INELIGIBLE", "NOPE")) {
            IngestionOutcome outcome = filteredJob(wanted, universe, instruments, coordinator, coverage).runScheduledWindow(to);

            org.assertj.core.api.Assertions.assertThat(outcome.status()).isEqualTo(IngestionStatus.INVALID_FILTER);
        }
        verify(coverage, never()).scheduled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(coordinator, never()).run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aReplayHonoursTheSameFilter() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        SearchUniverseProvider universe = mock(SearchUniverseProvider.class);
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        InstrumentCoverageService coverage = mock(InstrumentCoverageService.class);
        Instrument only = eligible("A", "AAA");
        when(universe.eligibleInstrumentIds()).thenReturn(List.of("A"));
        when(instruments.findAllById(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(only));
        Instant to = Instant.parse("2026-11-05T04:00:00Z");

        IngestionOutcome outcome = filteredJob("ZZZ", universe, instruments, coordinator, coverage)
                .replayWindow(to.minusSeconds(3600), to);

        org.assertj.core.api.Assertions.assertThat(outcome.status()).isEqualTo(IngestionStatus.INVALID_FILTER);
        verify(coordinator, never()).replay(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static IngestionProperties properties() {
        return new IngestionProperties(false, "tavily", Duration.ofMinutes(5),
                Duration.ofHours(4), 4, 10, 3, Duration.ofDays(90), new BigDecimal("0.20"), 2,
                new BigDecimal("0.50"),
                List.of("sgx.com"), List.of("earnings"));
    }
}
