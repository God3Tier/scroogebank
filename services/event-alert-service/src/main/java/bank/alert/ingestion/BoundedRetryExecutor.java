package bank.alert.ingestion;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class BoundedRetryExecutor {

    public <T> RetryResult<T> execute(int maximumAttempts, Supplier<T> operation) {
        if (maximumAttempts < 1 || maximumAttempts > 3) {
            throw new IllegalArgumentException("maximumAttempts must be between 1 and 3");
        }
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            try {
                return new RetryResult<>(operation.get(), attempt);
            } catch (SearchProviderException exception) {
                if (!exception.retryable() || attempt == maximumAttempts) {
                    throw new RetryExhaustedException(exception.code(), attempt, exception);
                }
            }
        }
        throw new IllegalStateException("retry loop ended unexpectedly");
    }
}
