package bank.alert.worker;

import bank.alert.config.AlertMode;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Turns the web server off when the service starts in WORKER mode: a worker
 * runs one ingestion window and exits, so it serves no API. ALERT_MODE stays
 * the only switch between the two modes.
 */
public class WorkerMode implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        if (isWorker(event.getEnvironment())) {
            event.getSpringApplication().setWebApplicationType(WebApplicationType.NONE);
        }
    }

    /** Matches alert.mode the way @ConditionalOnProperty does, ignoring case. */
    public static boolean isWorker(Environment environment) {
        return AlertMode.WORKER.name().equalsIgnoreCase(environment.getProperty("alert.mode"));
    }
}
