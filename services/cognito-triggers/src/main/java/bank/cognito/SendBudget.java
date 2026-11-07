package bank.cognito;

import java.time.Duration;
import java.util.function.LongSupplier;
import lombok.experimental.UtilityClass;

/** Retries a send while another whole attempt still fits before a deadline (ADR-0040 decision 4). */
@UtilityClass
class SendBudget {

    /** Each attempt is cut off by these socket timeouts (SqsQueueSender), with the SDK's own retries off. */
    final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    final Duration READ_TIMEOUT = Duration.ofSeconds(1);
    /** So no attempt can take longer than this. */
    final long ATTEMPT_NANOS = CONNECT_TIMEOUT.plus(READ_TIMEOUT).toNanos();
    /** The wait after the first failed attempt; it doubles after each later one. */
    final long FIRST_BACKOFF_NANOS = Duration.ofMillis(50).toNanos();

    /** How many attempts were made, and the last one's error, which is null when a send succeeded. */
    record Outcome(int attempts, RuntimeException error) {
        boolean sent() {
            return error == null;
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    /**
     * Runs sendOnce until it succeeds, or until another attempt might not finish by deadline, a
     * clock value in nanoseconds. Every failure is retried: the budget, not the kind of error,
     * decides when to stop. One attempt is always made.
     */
    Outcome sendWithin(Runnable sendOnce, long deadline, LongSupplier clock, Sleeper sleeper) {
        long backoff = FIRST_BACKOFF_NANOS;
        for (int attempts = 1; ; attempts++) {
            RuntimeException error;
            try {
                sendOnce.run();
                return new Outcome(attempts, null);
            } catch (RuntimeException e) {
                error = e;
            }
            if (clock.getAsLong() + backoff + ATTEMPT_NANOS - deadline > 0) {
                return new Outcome(attempts, error);
            }
            try {
                sleeper.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(attempts, error);
            }
            backoff *= 2;
        }
    }
}
