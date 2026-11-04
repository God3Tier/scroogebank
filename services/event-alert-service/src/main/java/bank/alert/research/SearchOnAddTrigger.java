package bank.alert.research;

import java.time.Duration;

/** Starts one targeted search of a company using a domain an agent just added (ADR-0080). */
public interface SearchOnAddTrigger {

    /** What to search: one company, over its last {@code lookback}, using only {@code domain}. */
    record ResearchRequest(String instrumentId, String domain, Duration lookback, String correlationId) {}

    /** Starts the search and returns at once; throws when it could not be started. */
    void start(ResearchRequest request);

    /** The environment variables the worker reads to run this search (ReplayProperties' sibling). */
    static java.util.Map<String, String> workerEnvironment(ResearchRequest request) {
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("ALERT_WORKER_RESEARCH_INSTRUMENT", request.instrumentId());
        env.put("ALERT_WORKER_RESEARCH_DOMAIN", request.domain());
        env.put("ALERT_WORKER_RESEARCH_LOOKBACK", request.lookback().toString());
        env.put("ALERT_WORKER_RESEARCH_CORRELATION_ID", request.correlationId());
        return env;
    }
}
