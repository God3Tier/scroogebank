package bank.clienthealth.metrics;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("client-health.metrics.cloudwatch")
public record CloudWatchMetricsProperties(
        boolean enabled,
        @NotBlank String namespace,
        @NotBlank String region,
        @NotNull Duration step
) {
    public CloudWatchMetricsProperties {
        if (step != null && (step.isZero() || step.isNegative())) {
            throw new IllegalArgumentException("CloudWatch metrics step must be positive");
        }
    }
}
