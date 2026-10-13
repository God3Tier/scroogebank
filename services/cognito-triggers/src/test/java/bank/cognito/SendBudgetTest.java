package bank.cognito;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

class SendBudgetTest {

    private static final long DEADLINE = Duration.ofSeconds(3).toNanos();

    /** A clock that moves only when the code under test sleeps, or a send takes time. */
    private static final class FakeTime {
        long now;
        final List<Long> sleeps = new ArrayList<>();
    }

    /** Fails the first `failures` calls, each taking `takes` nanoseconds, then succeeds. */
    @RequiredArgsConstructor
    private static final class FlakySend implements Runnable {
        private final FakeTime time;
        private final int failures;
        private final long takes;
        final List<Long> started = new ArrayList<>();

        @Override
        public void run() {
            started.add(time.now);
            time.now += takes;
            if (started.size() <= failures) {
                throw new IllegalStateException("attempt " + started.size() + " failed");
            }
        }
    }

    private static SendBudget.Outcome run(FakeTime time, Runnable send, long deadline) {
        return SendBudget.sendWithin(send, deadline, () -> time.now, nanos -> {
            time.sleeps.add(nanos);
            time.now += nanos;
        });
    }

    private static long millis(long millis) {
        return Duration.ofMillis(millis).toNanos();
    }

    @Test
    void aSendThatWorksIsMadeOnce() {
        FakeTime time = new FakeTime();

        SendBudget.Outcome outcome = run(time, new FlakySend(time, 0, 0), DEADLINE);

        assertThat(outcome).isEqualTo(new SendBudget.Outcome(1, null));
        assertThat(time.sleeps).isEmpty();
    }

    @Test
    void failedSendsAreRetriedWithDoublingWaits() {
        FakeTime time = new FakeTime();

        SendBudget.Outcome outcome = run(time, new FlakySend(time, 2, 0), DEADLINE);

        assertThat(outcome.sent()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(3);
        assertThat(time.sleeps).containsExactly(millis(50), millis(100));
    }

    @Test
    void retriesStopWhenAnotherAttemptMightNotFinishInTime() {
        FakeTime time = new FakeTime();
        FlakySend send = new FlakySend(time, 100, 0);

        SendBudget.Outcome outcome = run(time, send, DEADLINE);

        // Waits of 50, 100, 200 and 400 ms leave the next attempt starting at 1.55 seconds, too
        // late to be sure of finishing by 3.
        assertThat(outcome.attempts()).isEqualTo(5);
        assertThat(outcome.error()).hasMessage("attempt 5 failed");
        for (long started : send.started.subList(1, send.started.size())) {
            assertThat(started + SendBudget.ATTEMPT_NANOS).isLessThanOrEqualTo(DEADLINE);
        }
    }

    @Test
    void slowFailuresLeaveTimeForFewerRetries() {
        FakeTime time = new FakeTime();

        SendBudget.Outcome outcome = run(time, new FlakySend(time, 100, SendBudget.READ_TIMEOUT.toNanos()), DEADLINE);

        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.sent()).isFalse();
        assertThat(time.now).isLessThanOrEqualTo(DEADLINE);
    }

    @Test
    void oneAttemptIsMadeEvenWhenTheDeadlineHasPassed() {
        FakeTime time = new FakeTime();
        time.now = Duration.ofSeconds(10).toNanos();

        assertThat(run(time, new FlakySend(time, 0, 0), DEADLINE)).isEqualTo(new SendBudget.Outcome(1, null));
    }

    @Test
    void anInterruptedWaitStopsTheRetries() {
        RuntimeException failure = new IllegalStateException("queue unreachable");

        SendBudget.Outcome outcome = SendBudget.sendWithin(() -> {
            throw failure;
        }, DEADLINE, () -> 0L, nanos -> {
            throw new InterruptedException();
        });

        assertThat(outcome).isEqualTo(new SendBudget.Outcome(1, failure));
        assertThat(Thread.interrupted()).isTrue();
    }
}
