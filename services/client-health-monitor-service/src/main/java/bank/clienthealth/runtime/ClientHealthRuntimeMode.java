package bank.clienthealth.runtime;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/** Disables the web server before the analysis application context is built. */
public final class ClientHealthRuntimeMode
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {
    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        if (isAnalysis(event.getEnvironment())) {
            event.getSpringApplication().setWebApplicationType(WebApplicationType.NONE);
        }
    }

    public static boolean isAnalysis(Environment environment) {
        return ClientHealthRuntimeProperties.Mode.ANALYSIS.name().equalsIgnoreCase(
                environment.getProperty("client-health.runtime.mode"));
    }
}
