package bank.engagement.integration.sources;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * The approved client report in event-alert-service (ADR-0084), read and marked with the calling agent own token.
 * That service decides whether the agent approved this report for this client, so anything else is not found.
 */
public interface ReportDeliveries {

    /** The report and the fixed text for one approved client. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Delivery(UUID reportId, UUID alertId, String clientRef, String status, JsonNode report,
            List<Source> sources, String portfolioLine, String disclaimer) {

        public Delivery {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Source(String id, String title, String url, String domain) {
    }

    /** @throws SourceNotFoundException the agent did not approve this report for this client */
    Delivery get(UUID reportId, UUID alertId);

    /** @throws ReportAlreadySentException it was sent already or another request is sending it */
    void claim(UUID reportId, UUID alertId);

    void sent(UUID reportId, UUID alertId, String messageId);

    void release(UUID reportId, UUID alertId);
}
