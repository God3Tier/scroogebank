package bank.alert.audit;

import bank.alert.domain.AuditOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqsAuditDeliveryGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sendsAuditorEnvelopeToConfiguredQueue() throws Exception {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("message-1").build());
        SqsAuditDeliveryGateway gateway = new SqsAuditDeliveryGateway(
                sqs, mapper, "http://localhost:4566/000000000000/audit-insert");
        AuditorEnvelope envelope = envelope();

        gateway.publish(envelope.eventId(), envelope);

        ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(request.capture());
        assertThat(request.getValue().queueUrl()).endsWith("/audit-insert");
        assertThat(mapper.readTree(request.getValue().messageBody()).path("event_id").asText())
                .isEqualTo(envelope.eventId());
        assertThat(mapper.readTree(request.getValue().messageBody()).path("source_service").asText())
                .isEqualTo("EVENT_ALERT_SERVICE");
    }

    @Test
    void reportsSqsFailureSoOutboxRowCanBeRetried() {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SqsException.builder().message("unavailable").build());
        SqsAuditDeliveryGateway gateway = new SqsAuditDeliveryGateway(sqs, mapper, "queue-url");
        AuditorEnvelope envelope = envelope();

        assertThatThrownBy(() -> gateway.publish(envelope.eventId(), envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acknowledgement");
    }

    private AuditorEnvelope envelope() {
        UUID id = UUID.fromString("70000000-0000-4000-8000-000000000001");
        AuditOutbox outbox = new AuditOutbox(id, "alert.generated", 1,
                "{\"alertId\":\"synthetic\"}", Instant.parse("2026-09-30T00:00:00Z"));
        return new AuditorEnvelopeFactory(mapper).create(outbox);
    }
}
