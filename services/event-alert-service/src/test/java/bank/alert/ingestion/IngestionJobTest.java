package bank.alert.ingestion;

import bank.alert.config.AlertMode;
import bank.alert.config.AlertModeProperties;
import bank.alert.config.IngestionProperties;
import bank.alert.domain.Instrument;
import bank.alert.domain.InstrumentSourceDomain;
import bank.alert.repository.InstrumentRepository;
import bank.alert.repository.InstrumentSourceDomainRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
                searchUniverse, instruments, sourceDomains, coordinator, coverage);

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
                searchUniverse, instruments, sourceDomains, coordinator, coverage);
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
                searchUniverse, instruments, sourceDomains, coordinator, coverage);
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

    @Test
    void researchSearchesOneCompanyUsingOnlyItsAddedDomain() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        InstrumentSourceDomainRepository sourceDomains = mock(InstrumentSourceDomainRepository.class);
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        IngestionJob job = new IngestionJob(new AlertModeProperties(AlertMode.WORKER), properties(),
                mock(SearchUniverseProvider.class), instruments, sourceDomains, coordinator, mock(InstrumentCoverageService.class));
        Instant from = Instant.parse("2026-10-21T04:00:00Z");
        Instant to = Instant.parse("2026-11-04T04:00:00Z");
        Instrument instrument = mock(Instrument.class);
        when(instrument.getTicker()).thenReturn("D05");
        when(instrument.getIssuerName()).thenReturn("DBS Group Holdings Ltd");
        when(instrument.getSector()).thenReturn("Financials");
        when(instrument.getSearchAliases()).thenReturn(List.of("DBS"));
        when(instrument.isActive()).thenReturn(true);
        when(instruments.findById("SGX-D05")).thenReturn(java.util.Optional.of(instrument));
        when(sourceDomains.findByInstrumentIdAndActiveTrueOrderByAddedAtAsc("SGX-D05")).thenReturn(List.of(
                new InstrumentSourceDomain(java.util.UUID.randomUUID(), "SGX-D05", "businesstimes.com.sg", "agent-a", from)));

        job.researchCompany("SGX-D05", "businesstimes.com.sg", from, to);

        verify(coordinator).research(org.mockito.ArgumentMatchers.eq(from), org.mockito.ArgumentMatchers.eq(to),
                org.mockito.ArgumentMatchers.argThat(terms -> terms.size() == 1
                        && terms.getFirst().officialDomains().isEmpty()
                        && terms.getFirst().customDomains().equals(List.of("businesstimes.com.sg"))));
    }

    @Test
    void researchSearchesNothingForADomainThatIsNotAnActiveAddedDomain() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        InstrumentSourceDomainRepository sourceDomains = mock(InstrumentSourceDomainRepository.class);
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        IngestionJob job = new IngestionJob(new AlertModeProperties(AlertMode.WORKER), properties(),
                mock(SearchUniverseProvider.class), instruments, sourceDomains, coordinator, mock(InstrumentCoverageService.class));
        Instrument instrument = mock(Instrument.class);
        when(instrument.isActive()).thenReturn(true);
        when(instruments.findById("SGX-D05")).thenReturn(java.util.Optional.of(instrument));
        when(sourceDomains.findByInstrumentIdAndActiveTrueOrderByAddedAtAsc("SGX-D05")).thenReturn(List.of());

        IngestionOutcome outcome = job.researchCompany("SGX-D05", "random.example.com",
                Instant.parse("2026-10-21T04:00:00Z"), Instant.parse("2026-11-04T04:00:00Z"));

        assertThat(outcome.status()).isEqualTo(IngestionStatus.NO_QUERIES);
        verify(coordinator, never()).research(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void researchRefusesToRunInsideTheApiProcess() {
        IngestionJob job = new IngestionJob(new AlertModeProperties(AlertMode.API), properties(),
                mock(SearchUniverseProvider.class), mock(InstrumentRepository.class),
                mock(InstrumentSourceDomainRepository.class), mock(IngestionCoordinator.class), mock(InstrumentCoverageService.class));

        assertThatThrownBy(() -> job.researchCompany("SGX-D05", "example.com", Instant.EPOCH, Instant.now()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WORKER mode");
    }

    private static IngestionProperties properties() {
        return new IngestionProperties(false, "tavily", Duration.ofMinutes(5),
                Duration.ofHours(4), 4, 10, 3, Duration.ofDays(90), new BigDecimal("0.20"), 2,
                new BigDecimal("0.50"),
                List.of("sgx.com"), List.of("earnings"));
    }
}
