package bank.alert.ingestion;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedRetryExecutorTest {

    private final BoundedRetryExecutor executor = new BoundedRetryExecutor();

    @Test
    void retriesOnlyWithinTheConfiguredBound() {
        AtomicInteger calls = new AtomicInteger();

        RetryResult<String> result = executor.execute(3, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new SearchProviderException("TIMEOUT", true, "synthetic timeout");
            }
            return "ok";
        });

        assertThat(result.value()).isEqualTo("ok");
        assertThat(result.attempts()).isEqualTo(3);
    }

    @Test
    void doesNotRetryPermanentFailures() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(3, () -> {
            calls.incrementAndGet();
            throw new SearchProviderException("INVALID", false, "synthetic invalid request");
        })).isInstanceOf(RetryExhaustedException.class)
                .satisfies(error -> assertThat(((RetryExhaustedException) error).attempts()).isOne());
        assertThat(calls).hasValue(1);
    }
}
