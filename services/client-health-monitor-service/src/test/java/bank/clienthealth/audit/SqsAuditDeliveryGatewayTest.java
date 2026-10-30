package bank.clienthealth.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bank.clienthealth.audit.AuditDeliveryGateway.DeliveryException;
import bank.clienthealth.audit.AuditOutboxRepository.FailureCategory;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.Action;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsAuditDeliveryGatewayTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sendsAuditorSnakeCaseEnvelopeWithoutInternalFieldNames() throws Exception {
        SqsClient sqs = Mockito.mock(SqsClient.class);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("message-1").build());
        var gateway = new SqsAuditDeliveryGateway(
                sqs, json, "http://localhost:4566/queue/audit-insert");
        var event = RelationshipHealthAuditEventFactory.analysis(
                "run-1", Action.ANALYSIS_RUN_COMPLETED,
                Instant.parse("2026-10-26T00:00:00Z"), null);

        gateway.send(event);

        ArgumentCaptor<SendMessageRequest> request =
                ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(request.capture());
        var body = json.readTree(request.getValue().messageBody());
        assertEquals(event.eventId(), body.path("event_id").asText());
        assertEquals("RELATIONSHIP_HEALTH", body.path("event_type").asText());
        assertEquals("RELATIONSHIP_HEALTH_SERVICE", body.path("source_service").asText());
        assertEquals("1", body.path("payload").path("schema_version").asText());
        assertEquals("ANALYSIS_RUN", body.path("payload").path("entity_type").asText());
        assertFalse(request.getValue().messageBody().contains("schemaVersion"));
    }

    @Test
    void classifiesQueueRejectionWithoutLeakingProviderDetails() {
        SqsClient sqs = Mockito.mock(SqsClient.class);
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenThrow(
                SqsException.builder().statusCode(403).message("synthetic denied").build());
        var gateway = new SqsAuditDeliveryGateway(
                sqs, json, "https://sqs.example/audit-insert");

        DeliveryException failure = assertThrows(DeliveryException.class, () ->
                gateway.send(RelationshipHealthAuditEventFactory.analysis(
                        "run-1", Action.ANALYSIS_RUN_STARTED,
                        Instant.parse("2026-10-26T00:00:00Z"), null)));

        assertEquals(FailureCategory.DELIVERY_REJECTED, failure.category());
        assertEquals("Auditor queue rejected the event", failure.getMessage());
    }
}
