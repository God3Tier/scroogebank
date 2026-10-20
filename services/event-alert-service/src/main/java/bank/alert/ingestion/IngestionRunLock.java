package bank.alert.ingestion;

import java.util.Optional;

/**
 * Keeps two ingestion runs from searching at once. The run lease on
 * {@code search_run} only stops a second run of the exact same window; windows
 * that start at different times need this process-wide lock instead.
 */
public interface IngestionRunLock {
    /** Held until closed; closing releases the lock. Empty when another run holds it. */
    Optional<Hold> tryAcquire();

    interface Hold extends AutoCloseable {
        @Override
        void close();
    }
}
