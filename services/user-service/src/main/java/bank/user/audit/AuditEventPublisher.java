package bank.user.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Saves audit events to the outbox; AuditOutboxRelay sends them to SQS
 * (ADR-0020).
 *
 * <p>Callers publish after the request's own transaction has committed, so the
 * change and its event are not atomic: a crash between the two loses the
 * event. Once the outbox row is saved, an SQS or auditor outage only delays
 * the event.
 */
@Slf4j
@Component
public class AuditEventPublisher {

    private static final String EVENT_TYPE = "CRUD";
    private static final String SOURCE_SERVICE = "USER_MANAGEMENT";

    private final AuditOutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate ownTransaction;
    private final String queueUrl;

    public AuditEventPublisher(
            AuditOutboxRepository outbox,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${audit.sqs.queue-url:}") String queueUrl
    ) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        // Its own transaction, so an audit write that fails never rolls back,
        // or fails, the request it describes.
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.queueUrl = queueUrl;
    }

    public void publish(String action, String actorId, String resourceType, String resourceId, String endpoint,
                        String httpMethod, int statusCode, String userRole) {
        if (queueUrl.isBlank()) {
            // Nothing would ever send the row, so don't write one.
            log.debug("audit event skipped because AUDIT_QUEUE_URL is not configured");
            return;
        }
        if (actorId == null || actorId.isBlank()) {
            log.error("audit event skipped because the authenticated Cognito subject is missing");
            return;
        }

        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        AuditEvent event = new AuditEvent(
                eventId.toString(),
                EVENT_TYPE,
                action,
                SOURCE_SERVICE,
                actorId,
                new UserAuditPayload(resourceType, resourceId, endpoint, httpMethod, statusCode,
                        now.toString(), userRole)
        );
        try {
            String body = objectMapper.writeValueAsString(event);
            ownTransaction.executeWithoutResult(status -> outbox.save(new AuditOutboxEntry(eventId, body, now)));
        } catch (JsonProcessingException e) {
            log.error("could not serialize user audit event action={} event_id={}", action, eventId, e);
        } catch (RuntimeException e) {
            log.error("could not save user audit event action={} event_id={}", action, eventId, e);
        }
    }
}
