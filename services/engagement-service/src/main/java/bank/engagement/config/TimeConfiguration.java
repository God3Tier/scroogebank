package bank.engagement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class TimeConfiguration {

    /** Agents work in Singapore time: "today" and holiday windows use it. */
    public static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
