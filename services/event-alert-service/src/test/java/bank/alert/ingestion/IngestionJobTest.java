package bank.alert.ingestion;

import bank.alert.config.AlertMode;
import bank.alert.config.AlertModeProperties;
import bank.alert.config.IngestionProperties;
import bank.alert.repository.InstrumentRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

    private static IngestionProperties properties() {
        return new IngestionProperties(false, "agentcore-web-search-1.2.0", Duration.ofMinutes(5),
                Duration.ofHours(4), 4, 10, 3, Duration.ofDays(90), List.of("sgx.com"),
                List.of("earnings"));
    }
}
