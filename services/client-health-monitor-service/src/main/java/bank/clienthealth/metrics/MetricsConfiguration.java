package bank.clienthealth.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {
    @Bean
    ClientHealthMetrics clientHealthMetrics(MeterRegistry registry) {
        return new MicrometerClientHealthMetrics(registry);
    }
}
