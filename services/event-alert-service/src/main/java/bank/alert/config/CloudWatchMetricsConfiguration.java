package bank.alert.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

/**
 * Exports the service's Micrometer metrics to CloudWatch, off by default
 * (ALERT_CLOUDWATCH_METRICS_ENABLED=true turns it on). Spring Boot's CloudWatch
 * registry starts once this client bean exists. The run-once worker lives for
 * seconds, so the registry publishes its last values as the context closes
 * (ADR-0049). Region and credentials come from the AWS default chain, which is
 * how the ECS task role supplies them. Every distinct metric and tag combination
 * is a billed custom metric, so the switch stays deliberate.
 */
@Configuration
@ConditionalOnProperty(prefix = "alert.metrics.cloudwatch", name = "enabled", havingValue = "true")
public class CloudWatchMetricsConfiguration {

    @Bean(destroyMethod = "close")
    CloudWatchAsyncClient cloudWatchAsyncClient() {
        return CloudWatchAsyncClient.create();
    }
}
