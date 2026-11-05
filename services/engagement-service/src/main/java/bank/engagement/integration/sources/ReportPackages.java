package bank.engagement.integration.sources;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The event report and the chosen clients, from event-alert-service with the calling agent's token (ADR-0084).
 * That service rechecks ownership and the report version, and releases a client only if the alert is still the
 * agent's, linked to a CRM client and marked INFORMED.
 */
public interface ReportPackages {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Source(String id, String title, String url, String domain, Instant publishedAt, Instant retrievedAt,
            boolean usedInReport) {
    }

    /** {@code outcome} is OK or NOT_AUTHORISED; the other fields are null for NOT_AUTHORISED. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Recipient(UUID alertId, String outcome, String clientRef, String instrumentId, String issuer) {

        public boolean authorised() {
            return "OK".equals(outcome) && clientRef != null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Package(UUID eventId, UUID reportId, int version, String schemaVersion, JsonNode report, String reportHash,
            List<Source> sources, List<Recipient> recipients) {

        public Package {
            sources = sources == null ? List.of() : List.copyOf(sources);
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }
    }

    /**
     * @throws SourceNotFoundException      the agent has no alert on this event
     * @throws StaleReportVersionException  the report is not at {@code expectedVersion}
     * @throws SourceUnavailableException   event-alert-service could not answer; callers fail closed
     */
    Package fetch(UUID eventId, int expectedVersion, List<UUID> alertIds);
}
