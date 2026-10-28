package bank.clienthealth.metrics;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "client-health.metrics.cloudwatch",
        name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties(CloudWatchMetricsProperties.class)
public class CloudWatchMetricsConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    CloudWatchAsyncClient clientHealthCloudWatchClient(
            CloudWatchMetricsProperties properties
    ) {
        return CloudWatchAsyncClient.builder()
                .region(Region.of(properties.region()))
                .build();
    }

    @Bean
    CloudWatchMeterRegistry clientHealthCloudWatchRegistry(
            CloudWatchMetricsProperties properties,
            CloudWatchAsyncClient client
    ) {
        CloudWatchConfig config = new CloudWatchConfig() {
            @Override public String get(String key) { return null; }
            @Override public String namespace() { return properties.namespace(); }
            @Override public Duration step() { return properties.step(); }
        };
        return new CloudWatchMeterRegistry(config, Clock.SYSTEM, client);
    }
}
