package bank.dashboard.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * An audit event in the auditor's envelope.
 *
 * <p>The field names are snake_case because they have to be: this is the
 * {@code ValidEvent} shape the auditor reads from the shared audit queue.
 * The auditor accepts {@code source_service: DASHBOARD} with
 * {@code event_type: DASHBOARD_VIEW} and checks the payload
 * ({@code services/auditor/src/serverd/queue/payload_unmarshalling.go}).
 *
 * <p>{@code event_id} is new for every view. The queue delivers at least once,
 * and the auditor uses it to store a redelivered event only once.
 */
public record DashboardAuditEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("action") String action,
        @JsonProperty("source_service") String sourceService,
        @JsonProperty("actor_id") String actorId,
        @JsonProperty("payload") DashboardViewPayload payload
) {

    public static final String EVENT_TYPE = "DASHBOARD_VIEW";
    public static final String SOURCE_SERVICE = "DASHBOARD";

    public static DashboardAuditEvent of(DashboardAction action, String actorId, DashboardViewPayload payload) {
        return new DashboardAuditEvent(UUID.randomUUID().toString(), EVENT_TYPE, action.name(), SOURCE_SERVICE,
                actorId, payload);
    }
}
