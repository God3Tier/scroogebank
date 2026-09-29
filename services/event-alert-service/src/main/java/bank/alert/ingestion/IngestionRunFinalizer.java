package bank.alert.ingestion;

import bank.alert.domain.ProcessingWatermark;
import bank.alert.domain.SearchRun;
import bank.alert.repository.ProcessingWatermarkRepository;
import bank.alert.repository.SearchRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class IngestionRunFinalizer {

    private final SearchRunRepository searchRuns;
    private final ProcessingWatermarkRepository watermarks;

    public IngestionRunFinalizer(SearchRunRepository searchRuns, ProcessingWatermarkRepository watermarks) {
        this.searchRuns = searchRuns;
        this.watermarks = watermarks;
    }

    @Transactional
    public void succeed(SearchRun run, String provider, Instant windowTo, Duration overlap,
            int acceptedResults, Instant completedAt) {
        ProcessingWatermark watermark = watermarks.findById(provider)
                .orElseGet(() -> new ProcessingWatermark(provider, windowTo, overlap));
        watermark.advanceTo(windowTo, overlap);
        watermarks.save(watermark);
        run.succeed(acceptedResults, completedAt);
        searchRuns.save(run);
    }

    @Transactional
    public void fail(SearchRun run, String errorCode, Instant completedAt) {
        run.fail(errorCode, completedAt);
        searchRuns.save(run);
    }

    @Transactional
    public void succeedReplay(SearchRun run, int acceptedResults, Instant completedAt) {
        run.succeed(acceptedResults, completedAt);
        searchRuns.save(run);
    }
}
