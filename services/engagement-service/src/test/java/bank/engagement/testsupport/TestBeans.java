package bank.engagement.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * A movable clock fixed at 2026-10-21 10:00 Singapore time, and a mail channel
 * that records instead of sending.
 */
@TestConfiguration
public class TestBeans {

    public static final Instant NOW = Instant.parse("2026-10-21T02:00:00Z");

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(NOW);
    }

    @Bean
    public RecordingMailChannel recordingMailChannel() {
        return new RecordingMailChannel();
    }
}
