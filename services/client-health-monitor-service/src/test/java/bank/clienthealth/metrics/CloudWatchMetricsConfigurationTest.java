package bank.clienthealth.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

class CloudWatchMetricsConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CloudWatchMetricsConfiguration.class);

    @Test
    void exportIsDisabledByDefault() {
        context.run(result -> assertThat(result)
                .doesNotHaveBean(CloudWatchMeterRegistry.class)
                .doesNotHaveBean(CloudWatchAsyncClient.class));
    }

    @Test
    void enabledExportUsesValidatedPropertiesAndAnExternalClient() {
        context.withBean(CloudWatchAsyncClient.class,
                        () -> mock(CloudWatchAsyncClient.class))
                .withPropertyValues(
                        "client-health.metrics.cloudwatch.enabled=true",
                        "client-health.metrics.cloudwatch.namespace=Scrooge/Test",
                        "client-health.metrics.cloudwatch.region=ap-southeast-1",
                        "client-health.metrics.cloudwatch.step=1h")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    var registry = result.getBean(CloudWatchMeterRegistry.class);
                    assertThat(registry.config()).isNotNull();
                    var properties = result.getBean(CloudWatchMetricsProperties.class);
                    assertThat(properties.namespace()).isEqualTo("Scrooge/Test");
                    assertThat(properties.region()).isEqualTo("ap-southeast-1");
                });
    }
}
