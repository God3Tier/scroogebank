package bank.user.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditOutboxRelayTest {

    private static final String QUEUE_URL = "https://sqs.ap-southeast-1.amazonaws.com/000000000000/audit-insert";

    @Mock
    private AuditOutboxRepository outbox;

    @Mock
    private SqsClient sqsClient;

    private final AuditOutboxEntry first = entry("{\"n\":1}");
    private final AuditOutboxEntry second = entry("{\"n\":2}");
    private final AuditOutboxEntry third = entry("{\"n\":3}");

    @Test
    void sendsEachEntryUnchangedThenDeletesIt() {
        when(outbox.lockOldest(AuditOutboxRelay.BATCH_SIZE)).thenReturn(List.of(first, second));

        relay(QUEUE_URL).relay();

        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        InOrder order = inOrder(sqsClient, outbox);
        order.verify(sqsClient).sendMessage(sent.capture());
        order.verify(outbox).delete(first);
        order.verify(sqsClient).sendMessage(sent.capture());
        order.verify(outbox).delete(second);
        assertThat(sent.getAllValues()).extracting(SendMessageRequest::messageBody)
                .containsExactly("{\"n\":1}", "{\"n\":2}");
        assertThat(sent.getAllValues()).extracting(SendMessageRequest::queueUrl).containsOnly(QUEUE_URL);
    }

    @Test
    void stopsAtFirstFailureAndKeepsTheRest() {
        when(outbox.lockOldest(AuditOutboxRelay.BATCH_SIZE)).thenReturn(List.of(first, second, third));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().build())
                .thenThrow(new RuntimeException("SQS unavailable"));

        relay(QUEUE_URL).relay();

        verify(outbox).delete(first);
        verify(outbox, never()).delete(second);
        verify(outbox, never()).delete(third);
        // The third entry is never tried in this run.
        verify(sqsClient, times(2)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void doesNothingWithoutAQueue() {
        relay("").relay();

        verifyNoInteractions(outbox, sqsClient);
    }

    private AuditOutboxRelay relay(String queueUrl) {
        return new AuditOutboxRelay(outbox, sqsClient, queueUrl);
    }

    private static AuditOutboxEntry entry(String body) {
        return new AuditOutboxEntry(UUID.randomUUID(), body, Instant.parse("2026-10-09T06:00:00Z"));
    }
}
