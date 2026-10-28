package bank.clienthealth.analysis.port;

import java.time.Instant;
import java.util.Optional;

/** Read-only boundary for the latest durably completed analysis watermark. */
@FunctionalInterface
public interface SuccessfulAnalysisWatermarkQuery {
    Optional<Instant> latestSuccessfulWatermark();
}
