package bank.alert.ingestion;

import bank.alert.domain.ProcessingWatermark;
import bank.alert.domain.SearchRun;
import bank.alert.repository.ProcessingWatermarkRepository;
import bank.alert.repository.SearchRunRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionRunFinalizerTest {

    private static final String PROVIDER = "tavily";
    private static final Instant FROM = Instant.parse("2026-09-28T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-28T04:00:00Z");

    @Test
    void advancesTheWatermarkOnlyWithSuccessfulRunCompletion() {
        SearchRunRepository runs = mock(SearchRunRepository.class);
        ProcessingWatermarkRepository watermarks = mock(ProcessingWatermarkRepository.class);
        ProcessingWatermark watermark = new ProcessingWatermark(PROVIDER, FROM, Duration.ofMinutes(5));
        when(watermarks.findById(PROVIDER)).thenReturn(Optional.of(watermark));
        SearchRun run = new SearchRun(UUID.randomUUID(), FROM.minus(Duration.ofMinutes(5)), TO, FROM);
        IngestionRunFinalizer finalizer = new IngestionRunFinalizer(runs, watermarks);

        finalizer.succeed(run, PROVIDER, TO, Duration.ofMinutes(5), 2, TO);

        assertThat(watermark.getLastSuccessfulTo()).isEqualTo(TO);
        assertThat(run.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(run.getResultCount()).isEqualTo(2);
        verify(watermarks).save(watermark);
        verify(runs).save(run);
    }

    @Test
    void failureLeavesTheWatermarkUntouched() {
        SearchRunRepository runs = mock(SearchRunRepository.class);
        ProcessingWatermarkRepository watermarks = mock(ProcessingWatermarkRepository.class);
        SearchRun run = new SearchRun(UUID.randomUUID(), FROM, TO, FROM);
        IngestionRunFinalizer finalizer = new IngestionRunFinalizer(runs, watermarks);

        finalizer.fail(run, "TIMEOUT", TO);

        assertThat(run.getStatus()).isEqualTo("FAILED");
        verify(watermarks, never()).save(org.mockito.ArgumentMatchers.any());
        verify(runs).save(run);
    }

    @Test
    void replaySuccessLeavesTheSchedulerWatermarkUntouched() {
        SearchRunRepository runs = mock(SearchRunRepository.class);
        ProcessingWatermarkRepository watermarks = mock(ProcessingWatermarkRepository.class);
        SearchRun run = new SearchRun(UUID.randomUUID(), FROM, TO, FROM);
        IngestionRunFinalizer finalizer = new IngestionRunFinalizer(runs, watermarks);

        finalizer.succeedReplay(run, 3, TO);

        assertThat(run.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(run.getResultCount()).isEqualTo(3);
        verify(watermarks, never()).save(org.mockito.ArgumentMatchers.any());
        verify(runs).save(run);
    }
}
