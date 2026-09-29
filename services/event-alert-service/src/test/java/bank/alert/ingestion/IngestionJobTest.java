package bank.alert.ingestion;

import bank.alert.config.AlertMode;
import bank.alert.config.AlertModeProperties;
import bank.alert.config.IngestionProperties;
import bank.alert.domain.Instrument;
import bank.alert.repository.InstrumentRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
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
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        IngestionJob job = new IngestionJob(new AlertModeProperties(AlertMode.API), properties(),
                instruments, coordinator);

        assertThatThrownBy(() -> job.runScheduledWindow(Instant.parse("2026-09-28T04:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORKER mode");
        verify(instruments, never()).findByActiveTrueOrderByIdAsc();
        verify(coordinator, never()).run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void invokesBoundedReplayOnlyFromTheWorkerProcess() {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
        IngestionJob job = new IngestionJob(new AlertModeProperties(AlertMode.WORKER), properties(),
                instruments, coordinator);
        Instant from = Instant.parse("2026-09-28T02:00:00Z");
        Instant to = Instant.parse("2026-09-28T04:00:00Z");
        Instrument instrument = mock(Instrument.class);
        when(instrument.getTicker()).thenReturn("SYN");
        when(instrument.getIssuerName()).thenReturn("Synthetic Issuer");
        when(instruments.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(instrument));

        job.replayWindow(from, to);

        verify(coordinator).replay(org.mockito.ArgumentMatchers.eq(from),
                org.mockito.ArgumentMatchers.eq(to), org.mockito.ArgumentMatchers.argThat(terms ->
                        terms.size() == 1 && terms.getFirst().ticker().equals("SYN")));
    }

    private static IngestionProperties properties() {
        return new IngestionProperties(false, "agentcore-web-search-1.2.0", Duration.ofMinutes(5),
                Duration.ofHours(4), 4, 10, 3, Duration.ofDays(90), List.of("sgx.com"),
                List.of("earnings"));
    }
}
