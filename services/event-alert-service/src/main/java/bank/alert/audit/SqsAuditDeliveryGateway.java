package bank.alert.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
@ConditionalOnProperty(prefix = "alert.audit.publisher", name = "enabled", havingValue = "true")
public class SqsAuditDeliveryGateway implements AuditDeliveryGateway {

    private final SqsClient sqs;
    private final ObjectMapper mapper;
    private final String queueUrl;

    public SqsAuditDeliveryGateway(SqsClient sqs, ObjectMapper mapper,
            @Value("${alert.audit.publisher.queue-url}") String queueUrl) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalArgumentException("audit SQS queue URL is required when publishing is enabled");
        }
        this.sqs = sqs;
        this.mapper = mapper;
        this.queueUrl = queueUrl;
    }

    @Override
    public void publish(String eventId, AuditorEnvelope envelope) {
        if (!eventId.equals(envelope.eventId())) {
            throw new IllegalArgumentException("outbox event ID does not match the auditor envelope");
        }
        try {
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(mapper.writeValueAsString(envelope))
                    .build());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("cannot serialize auditor envelope", exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("auditor acknowledgement was not received", exception);
        }
    }
}
