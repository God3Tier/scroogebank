package bank.clienthealth.metrics;

import bank.clienthealth.analysis.port.SuccessfulAnalysisWatermarkQuery;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {
    @Bean
    ClientHealthMetrics clientHealthMetrics(
            MeterRegistry registry,
            ObjectProvider<SuccessfulAnalysisWatermarkQuery> watermarkQueries
    ) {
        SuccessfulAnalysisWatermarkQuery query =
                watermarkQueries.getIfAvailable(() -> java.util.Optional::empty);
        return new MicrometerClientHealthMetrics(registry, query);
    }
}
