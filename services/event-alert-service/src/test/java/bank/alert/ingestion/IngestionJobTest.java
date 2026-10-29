package bank.alert.ingestion;

import bank.alert.config.AlertMode;
import bank.alert.config.AlertModeProperties;
import bank.alert.config.IngestionProperties;
import bank.alert.domain.Instrument;
import bank.alert.repository.InstrumentRepository;
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
        SearchUniverseProvider searchUniverse = mock(SearchUniverseProvider.class);
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        InstrumentCoverageService coverage = mock(InstrumentCoverageService.class);
        IngestionJob job = new IngestionJob(new AlertModeProperties(AlertMode.API), properties(),
                searchUniverse, instruments, coordinator, coverage);

        assertThatThrownBy(() -> job.runScheduledWindow(Instant.parse("2026-09-28T04:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORKER mode");
        verify(searchUniverse, never()).eligibleInstrumentIds();
        verify(coordinator, never()).run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void invokesBoundedReplayOnlyFromTheWorkerProcess() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        SearchUniverseProvider searchUniverse = mock(SearchUniverseProvider.class);
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        InstrumentCoverageService coverage = mock(InstrumentCoverageService.class);
        IngestionJob job = new IngestionJob(new AlertModeProperties(AlertMode.WORKER), properties(),
                searchUniverse, instruments, coordinator, coverage);
        Instant from = Instant.parse("2026-09-28T02:00:00Z");
        Instant to = Instant.parse("2026-09-28T04:00:00Z");
        Instrument instrument = mock(Instrument.class);
        when(instrument.getTicker()).thenReturn("SYN");
        when(instrument.getIssuerName()).thenReturn("Synthetic Issuer");
        when(instrument.getSector()).thenReturn("Synthetic");
        when(instrument.getSearchAliases()).thenReturn(List.of("Synthetic Issuer"));
        when(instrument.getOfficialDomains()).thenReturn(List.of("sgx.com"));
        when(instrument.getId()).thenReturn("SYN-SGX-001");
        when(instrument.isActive()).thenReturn(true);
        Instrument inactive = mock(Instrument.class);
        when(inactive.getId()).thenReturn("INACTIVE");
        when(inactive.isActive()).thenReturn(false);
        when(searchUniverse.eligibleInstrumentIds())
                .thenReturn(List.of("SYN-SGX-001", "INACTIVE", "UNKNOWN"));
        when(instruments.findAllById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(inactive, instrument));
        when(coverage.replay(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(to)))
                .thenReturn(List.of("SYN-SGX-001"));

        job.replayWindow(from, to);

        verify(coordinator).replay(org.mockito.ArgumentMatchers.eq(from),
                org.mockito.ArgumentMatchers.eq(to), org.mockito.ArgumentMatchers.argThat(terms ->
                        terms.size() == 1 && terms.getFirst().ticker().equals("SYN")
                                && terms.getFirst().aliases().equals(List.of("Synthetic Issuer"))
                                && terms.getFirst().officialDomains().equals(List.of("sgx.com"))));
    }

    private static IngestionProperties properties() {
        return new IngestionProperties(false, "tavily", Duration.ofMinutes(5),
                Duration.ofHours(4), 4, 10, 3, Duration.ofDays(90), new BigDecimal("0.20"), 2,
                new BigDecimal("0.50"),
                List.of("sgx.com"), List.of("earnings"));
    }
}
