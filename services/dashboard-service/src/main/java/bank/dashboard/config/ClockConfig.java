package bank.dashboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * Injected rather than called statically so window defaults and
     * {@code generatedAt} are testable. UTC because every timestamp the
     * dashboard reports crosses a service boundary.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
