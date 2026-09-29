package bank.alert.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(prefix = "alert.audit.publisher", name = "enabled", havingValue = "true")
public class KafkaAuditDeliveryGateway implements AuditDeliveryGateway {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final String topic;
    private final Duration timeout;

    public KafkaAuditDeliveryGateway(KafkaTemplate<String, String> kafka, ObjectMapper mapper,
            @Value("${alert.audit.publisher.topic:audit.insert}") String topic,
            @Value("${alert.audit.publisher.send-timeout:10s}") Duration timeout) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.topic = topic;
        this.timeout = timeout;
    }

    @Override
    public void publish(String eventId, AuditorEnvelope envelope) {
        try {
            kafka.send(topic, eventId, mapper.writeValueAsString(envelope))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("cannot serialize auditor envelope", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("auditor acknowledgement was not received", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("auditor acknowledgement was not received", exception);
        }
    }
}
