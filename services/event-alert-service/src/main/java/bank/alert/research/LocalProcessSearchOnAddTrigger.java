package bank.alert.research;

import bank.alert.config.SearchOnAddProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * For a developer's machine only (ADR-0080): runs the configured command, for example
 * {@code java -jar event-alert-service.jar}, as a child process in WORKER mode with the research
 * variables set. The command comes from configuration, never from a request, and the values passed
 * to it are a company id and a domain that have already passed the domain policy.
 */
public class LocalProcessSearchOnAddTrigger implements SearchOnAddTrigger {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessSearchOnAddTrigger.class);

    private final SearchOnAddProperties properties;

    public LocalProcessSearchOnAddTrigger(SearchOnAddProperties properties) {
        this.properties = properties;
        if (properties.command().isEmpty()) {
            throw new IllegalStateException("alert.search-on-add.command is required in local-process mode");
        }
    }

    @Override
    public void start(ResearchRequest request) {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(properties.command()));
        // The child is a second copy of this service, so it needs its own, free port.
        builder.environment().put("SERVER_PORT", "0");
        builder.environment().put("MANAGEMENT_SERVER_PORT", "0");
        builder.environment().put("ALERT_MODE", "WORKER");
        builder.environment().putAll(SearchOnAddTrigger.workerEnvironment(request));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        try {
            Process process = builder.start();
            process.onExit().thenAccept(done ->
                    log.info("local research search finished exit_code={} correlation_id={}",
                            done.exitValue(), request.correlationId()));
        } catch (IOException cannotStart) {
            throw new IllegalStateException("the local worker process could not be started", cannotStart);
        }
    }

    List<String> command() {
        return properties.command();
    }
}
