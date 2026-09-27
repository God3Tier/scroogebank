package bank.alert.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({AlertModeProperties.class, IngestionProperties.class})
public class AlertServiceConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
