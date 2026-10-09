package bank.user.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditEventPublisherTest {

    private static final String QUEUE_URL = "https://sqs.ap-southeast-1.amazonaws.com/000000000000/audit-insert";

    @Mock
    private AuditOutboxRepository outbox;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void savesTheEventToTheOutboxInItsOwnTransaction() throws Exception {
        publisher(QUEUE_URL).publish("DISABLE", "sub-admin", "user-1", "/api/users/{id}/disable", "PATCH", 204, "AGENT");

        ArgumentCaptor<AuditOutboxEntry> saved = ArgumentCaptor.forClass(AuditOutboxEntry.class);
        verify(outbox).save(saved.capture());
        JsonNode body = objectMapper.readTree(saved.getValue().getBody());
        assertThat(body.get("event_id").asText()).isEqualTo(saved.getValue().getEventId().toString());
        assertThat(body.get("event_type").asText()).isEqualTo("CRUD");
        assertThat(body.get("action").asText()).isEqualTo("DISABLE");
        assertThat(body.get("source_service").asText()).isEqualTo("USER_MANAGEMENT");
        assertThat(body.get("actor_id").asText()).isEqualTo("sub-admin");
        assertThat(body.at("/payload/resource_id").asText()).isEqualTo("user-1");
        assertThat(body.at("/payload/endpoint").asText()).isEqualTo("/api/users/{id}/disable");
        assertThat(body.at("/payload/status_code").asInt()).isEqualTo(204);

        ArgumentCaptor<TransactionDefinition> transaction = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(transaction.capture());
        assertThat(transaction.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void skipsWhenNoQueueIsConfigured() {
        publisher("").publish("READ", "sub-admin", "user-1", "/api/users/{id}", "GET", 200, "AGENT");

        verifyNoInteractions(outbox, transactionManager);
    }

    @Test
    void skipsWhenTheActorIsMissing() {
        publisher(QUEUE_URL).publish("READ", null, "user-1", "/api/users/{id}", "GET", 200, "AGENT");

        verifyNoInteractions(outbox, transactionManager);
    }

    @Test
    void anOutboxFailureDoesNotReachTheCaller() {
        when(outbox.save(any())).thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatCode(() -> publisher(QUEUE_URL)
                .publish("DISABLE", "sub-admin", "user-1", "/api/users/{id}/disable", "PATCH", 204, "AGENT"))
                .doesNotThrowAnyException();
    }

    private AuditEventPublisher publisher(String queueUrl) {
        return new AuditEventPublisher(outbox, objectMapper, transactionManager, queueUrl);
    }
}
