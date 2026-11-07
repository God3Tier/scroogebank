package bank.alert.research;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Starts one targeted search of some companies, after a website was added or Search now was pressed (ADR-0081). */
public interface SearchOnAddTrigger {

    /**
     * What to search: these companies, over their last {@code lookback}. With a {@code domain} only that
     * added website is searched; with none, every website is.
     */
    record ResearchRequest(List<String> instrumentIds, String domain, Duration lookback, String correlationId) {}

    /** Starts the search and returns at once; throws when it could not be started. */
    void start(ResearchRequest request);

    /** The environment variables the worker reads to run this search. */
    static Map<String, String> workerEnvironment(ResearchRequest request) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ALERT_WORKER_RESEARCH_INSTRUMENTS", String.join(",", request.instrumentIds()));
        if (request.domain() != null) env.put("ALERT_WORKER_RESEARCH_DOMAIN", request.domain());
        env.put("ALERT_WORKER_RESEARCH_LOOKBACK", request.lookback().toString());
        env.put("ALERT_WORKER_RESEARCH_CORRELATION_ID", request.correlationId());
        return env;
    }
}
