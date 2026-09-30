package bank.user.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
public class AuditEventPublisher {

    private static final String EVENT_TYPE = "CRUD";
    private static final String SOURCE_SERVICE = "USER_MANAGEMENT";

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String queueUrl;

    public AuditEventPublisher(
            SqsClient sqsClient,
            ObjectMapper objectMapper,
            @Value("${audit.sqs.queue-url:}") String queueUrl
    ) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.queueUrl = queueUrl;
    }

    public void publish(String action, String actorId, String resourceId, String endpoint,
                        String httpMethod, int statusCode, String userRole) {
        if (queueUrl.isBlank()) {
            log.debug("audit event skipped because AUDIT_QUEUE_URL is not configured");
            return;
        }
        if (actorId == null || actorId.isBlank()) {
            log.error("audit event skipped because the authenticated Cognito subject is missing");
            return;
        }

        AuditEvent event = new AuditEvent(
                UUID.randomUUID().toString(),
                EVENT_TYPE,
                action,
                SOURCE_SERVICE,
                actorId,
                new UserAuditPayload("USER", resourceId, endpoint, httpMethod, statusCode,
                        Instant.now().toString(), userRole)
        );
        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(objectMapper.writeValueAsString(event))
                    .build());
        } catch (JsonProcessingException e) {
            log.error("could not serialize user audit event action={} event_id={}", action, event.eventId(), e);
        } catch (RuntimeException e) {
            log.error("could not send user audit event action={} event_id={}", action, event.eventId(), e);
        }
    }
}
