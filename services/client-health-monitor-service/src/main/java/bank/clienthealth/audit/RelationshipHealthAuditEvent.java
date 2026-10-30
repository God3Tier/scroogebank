package bank.clienthealth.audit;

import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.task.domain.RelationshipTask.Status;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Redacted, provider-independent envelope for the shared Auditor queue. */
public record RelationshipHealthAuditEvent(
        String eventId,
        Action action,
        String actorId,
        Payload payload
) {
    public static final String EVENT_TYPE = "RELATIONSHIP_HEALTH";
    public static final String SOURCE_SERVICE = "RELATIONSHIP_HEALTH_SERVICE";
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public RelationshipHealthAuditEvent {
        if (eventId == null || !SHA_256.matcher(eventId).matches()) {
            throw new IllegalArgumentException("eventId must be lowercase SHA-256");
        }
        action = Objects.requireNonNull(action);
        actorId = requireIdentifier(actorId, "actorId");
        payload = Objects.requireNonNull(payload);
    }

    public enum Action {
        ANALYSIS_RUN_STARTED,
        ANALYSIS_RUN_COMPLETED,
        ANALYSIS_RUN_FAILED,
        SIGNAL_DETECTED,
        SIGNAL_SUPPRESSED,
        TASK_CREATED,
        TASK_EVIDENCE_ADDED,
        TASK_ACCEPTED,
        TASK_COMPLETED,
        TASK_DISMISSED,
        EXPLANATION_GENERATED,
        EXPLANATION_FALLBACK_USED,
        OUTBOX_DELIVERY_RECOVERED
    }

    public enum EntityType {
        ANALYSIS_RUN,
        RELATIONSHIP_TASK,
        FINDING,
        SIGNAL,
        EXPLANATION,
        OUTBOX
    }

    public record Payload(
            String schemaVersion,
            EntityType entityType,
            String entityId,
            Instant occurredAt,
            String correlationId,
            String clientRef,
            AuditState before,
            AuditState after,
            String dispositionCode
    ) {
        public Payload {
            if (!"1".equals(schemaVersion)) {
                throw new IllegalArgumentException("unsupported audit payload schema");
            }
            entityType = Objects.requireNonNull(entityType);
            entityId = requireIdentifier(entityId, "entityId");
            occurredAt = Objects.requireNonNull(occurredAt);
            correlationId = requireIdentifier(correlationId, "correlationId");
            if (clientRef != null && !SHA_256.matcher(clientRef).matches()) {
                throw new IllegalArgumentException("clientRef must be a one-way SHA-256 reference");
            }
            if (dispositionCode != null) {
                dispositionCode = requireIdentifier(dispositionCode, "dispositionCode");
            }
        }
    }

    public record AuditState(Status status, Severity severity, Long taskVersion) {
        public AuditState {
            if (taskVersion != null && taskVersion <= 0) {
                throw new IllegalArgumentException("taskVersion must be positive");
            }
        }
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded opaque identifier");
        }
        return value;
    }
}
