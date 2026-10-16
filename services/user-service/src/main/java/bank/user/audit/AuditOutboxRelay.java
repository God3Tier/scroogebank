package bank.user.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Sends audit events from the outbox to the auditor's SQS queue (ADR-0020).
 *
 * <p>Delivery is at least once. If the task dies after SQS accepts an event
 * but before its row's delete commits, the event is sent again on a later run,
 * and the auditor drops the repeat by event_id. A row leaves the outbox only
 * after SQS has accepted it, so an SQS outage delays events instead of losing
 * them.
 */
@Slf4j
@Component
public class AuditOutboxRelay {

    static final int BATCH_SIZE = 50;

    private final AuditOutboxRepository outbox;
    private final SqsClient sqsClient;
    private final String queueUrl;

    public AuditOutboxRelay(
            AuditOutboxRepository outbox,
            SqsClient sqsClient,
            @Value("${audit.sqs.queue-url:}") String queueUrl
    ) {
        this.outbox = outbox;
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void relay() {
        if (queueUrl.isBlank()) {
            return;
        }
        for (AuditOutboxEntry entry : outbox.lockOldest(BATCH_SIZE)) {
            try {
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(entry.getBody())
                        .build());
            } catch (RuntimeException e) {
                // Stop and keep the rest for the next run. Entries already
                // sent are still deleted when this transaction commits.
                log.warn("audit event {} not sent; will retry: {}", entry.getEventId(), e.toString());
                return;
            }
            outbox.delete(entry);
        }
    }
}
