package bank.alert.ingestion;

import java.time.Instant;
import java.util.UUID;

public record IngestionOutcome(
        IngestionStatus status,
        UUID runId,
        Instant windowFrom,
        Instant windowTo,
        int searchAttempts,
        int acceptedResults,
        int duplicateResults,
        int rejectedResults,
        String errorCode) {

    public static IngestionOutcome skipped(IngestionStatus status, Instant windowTo) {
        return new IngestionOutcome(status, null, null, windowTo, 0, 0, 0, 0, null);
    }
}
