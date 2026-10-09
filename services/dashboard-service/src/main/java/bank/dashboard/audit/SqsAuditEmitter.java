package bank.dashboard.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Sends each view to the shared audit queue, where the auditor adds it to the
 * hash chain (ADR-0033, ADR-0020).
 *
 * <p>Best-effort, as ADR-0033 decides: there is no database to hold an outbox,
 * and auditing a read must not take the read down. A failed send is logged and
 * that view goes unrecorded.
 */
@Component
@Slf4j
public class SqsAuditEmitter implements AuditEmitter {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String queueUrl;

    public SqsAuditEmitter(
            SqsClient auditSqsClient,
            ObjectMapper objectMapper,
            @Value("${audit.sqs.queue-url:}") String queueUrl
    ) {
        this.sqsClient = auditSqsClient;
        this.objectMapper = objectMapper;
        this.queueUrl = queueUrl;
    }

    @Override
    public void emit(DashboardAuditEvent event) {
        if (queueUrl.isBlank()) {
            log.debug("audit event skipped because AUDIT_QUEUE_URL is not configured");
            return;
        }
        if (event.actorId() == null || event.actorId().isBlank()) {
            // The auditor would reject it: a view must name who looked.
            log.error("audit event skipped because the authenticated Cognito subject is missing");
            return;
        }
        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(objectMapper.writeValueAsString(event))
                    .build());
        } catch (JsonProcessingException e) {
            log.error("could not serialize dashboard audit event action={} event_id={}", event.action(), event.eventId(), e);
        } catch (RuntimeException e) {
            log.error("could not send dashboard audit event action={} event_id={}", event.action(), event.eventId(), e);
        }
    }
}
