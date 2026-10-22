package bank.alert.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The audit publisher and retention cleanup run on timers in the API process
 * only. A worker runs one window and exits, so it starts neither; the API
 * process publishes any audit rows the worker leaves in the shared outbox.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "alert", name = "mode", havingValue = "API")
public class SchedulingConfiguration {
}
