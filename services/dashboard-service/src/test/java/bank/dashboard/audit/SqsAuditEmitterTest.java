package bank.dashboard.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SqsAuditEmitterTest {

    private static final String QUEUE = "https://sqs.ap-southeast-1.amazonaws.com/000000000000/audit-insert";

    private final SqsClient sqs = mock(SqsClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsTheEventToTheAuditQueue() throws Exception {
        DashboardAuditEvent event = event("agent-sub");

        new SqsAuditEmitter(sqs, objectMapper, QUEUE).emit(event);

        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(sent.capture());
        assertThat(sent.getValue().queueUrl()).isEqualTo(QUEUE);
        JsonNode body = objectMapper.readTree(sent.getValue().messageBody());
        assertThat(body.get("event_id").asText()).isEqualTo(event.eventId());
        assertThat(body.get("source_service").asText()).isEqualTo("DASHBOARD");
        assertThat(body.get("actor_id").asText()).isEqualTo("agent-sub");
        assertThat(body.get("payload").get("scope").asText()).isEqualTo("AGENT");
    }

    @Test
    void sendsNothingWithoutAQueue() {
        // Running locally without AWS.
        new SqsAuditEmitter(sqs, objectMapper, "").emit(event("agent-sub"));

        verifyNoInteractions(sqs);
    }

    @Test
    void sendsNothingWithoutAnActor() {
        new SqsAuditEmitter(sqs, objectMapper, QUEUE).emit(event(" "));

        verifyNoInteractions(sqs);
    }

    @Test
    void aFailedSendDoesNotThrow() {
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenThrow(SdkClientException.create("queue unreachable"));

        assertThatCode(() -> new SqsAuditEmitter(sqs, objectMapper, QUEUE).emit(event("agent-sub")))
                .doesNotThrowAnyException();
    }

    private static DashboardAuditEvent event(String actor) {
        return DashboardAuditEvent.of(DashboardAction.VIEW_OVERVIEW, actor, new DashboardViewPayload(
                "AGENT", "2026-09-01", "2026-09-30", Map.of("clients", "OK"), 1, false, "2026-09-30T04:10:00Z"));
    }
}
