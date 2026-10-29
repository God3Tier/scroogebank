package bank.engagement.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runs the mail dispatcher and the audit relay. Tests turn it off and call
 * them directly.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "engagement.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfiguration {
}
