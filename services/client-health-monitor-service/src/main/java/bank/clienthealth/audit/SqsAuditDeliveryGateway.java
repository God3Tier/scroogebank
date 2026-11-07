package bank.clienthealth.audit;

import bank.clienthealth.audit.AuditDeliveryGateway.DeliveryException;
import bank.clienthealth.audit.AuditOutboxRepository.FailureCategory;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.AuditState;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.Payload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

/** Sends the Auditor's snake_case envelope to the existing shared SQS queue. */
public final class SqsAuditDeliveryGateway implements AuditDeliveryGateway {
    private final SqsClient sqs;
    private final ObjectMapper json;
    private final String queueUrl;

    public SqsAuditDeliveryGateway(
            SqsClient sqs,
            ObjectMapper json,
            String queueUrl
    ) {
        this.sqs = java.util.Objects.requireNonNull(sqs);
        this.json = java.util.Objects.requireNonNull(json);
        if (!StringUtils.hasText(queueUrl)) {
            throw new IllegalArgumentException("AUDIT_QUEUE_URL is required");
        }
        this.queueUrl = queueUrl;
    }

    @Override
    public void send(RelationshipHealthAuditEvent event) {
        try {
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody(event))
                    .build());
        } catch (SqsException rejected) {
            FailureCategory category = rejected.statusCode() >= 400
                    && rejected.statusCode() < 500
                    ? FailureCategory.DELIVERY_REJECTED
                    : FailureCategory.DELIVERY_UNAVAILABLE;
            throw delivery(category, "Auditor queue rejected the event", rejected);
        } catch (SdkClientException unavailable) {
            throw delivery(
                    FailureCategory.DELIVERY_UNAVAILABLE,
                    "Auditor queue is unavailable",
                    unavailable);
        }
    }

    String messageBody(RelationshipHealthAuditEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", event.eventId());
        envelope.put("event_type", RelationshipHealthAuditEvent.EVENT_TYPE);
        envelope.put("action", event.action().name());
        envelope.put("source_service", RelationshipHealthAuditEvent.SOURCE_SERVICE);
        envelope.put("actor_id", event.actorId());
        envelope.put("payload", payload(event.payload()));
        try {
            return json.writeValueAsString(envelope);
        } catch (JsonProcessingException invalid) {
            throw delivery(
                    FailureCategory.DELIVERY_REJECTED,
                    "Audit event cannot be serialized",
                    invalid);
        }
    }

    private static Map<String, Object> payload(Payload payload) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema_version", payload.schemaVersion());
        value.put("entity_type", payload.entityType().name());
        value.put("entity_id", payload.entityId());
        value.put("occurred_at", payload.occurredAt().toString());
        value.put("correlation_id", payload.correlationId());
        if (payload.clientRef() != null) value.put("client_ref", payload.clientRef());
        if (payload.before() != null) value.put("before", state(payload.before()));
        if (payload.after() != null) value.put("after", state(payload.after()));
        if (payload.dispositionCode() != null) {
            value.put("disposition_code", payload.dispositionCode());
        }
        return value;
    }

    private static Map<String, Object> state(AuditState state) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (state.status() != null) value.put("status", state.status().name());
        if (state.severity() != null) value.put("severity", state.severity().name());
        if (state.taskVersion() != null) value.put("task_version", state.taskVersion());
        return value;
    }

    private static DeliveryException delivery(
            FailureCategory category,
            String message,
            Exception cause
    ) {
        DeliveryException failure = new DeliveryException(category, message);
        failure.initCause(cause);
        return failure;
    }
}
