package bank.alert.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaAuditDeliveryGatewayIntegrationTest {

    private static final String TOPIC = "audit.insert";

    @Test
    void waitsForBrokerAcknowledgementAndPublishesAuditorEnvelope() {
        EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
        broker.afterPropertiesSet();
        var producerProperties = KafkaTestUtils.producerProps(broker);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        var producerFactory = new DefaultKafkaProducerFactory<String, String>(
                producerProperties);
        KafkaTemplate<String, String> kafka = new KafkaTemplate<>(producerFactory);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        KafkaAuditDeliveryGateway gateway = new KafkaAuditDeliveryGateway(
                kafka, mapper, TOPIC, Duration.ofSeconds(5));

        var consumerProperties = KafkaTestUtils.consumerProps("alert-audit-test", "true", broker);
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(
                consumerProperties)
                .createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, TOPIC);
            gateway.publish("70000000-0000-4000-8000-000000000001",
                    new AuditorEnvelope("EVENT_ALERT", "GENERATED", "EVENT_ALERT_SERVICE",
                            "event-alert-service", JsonNodeFactory.instance.objectNode()
                                    .put("event_id", "70000000-0000-4000-8000-000000000001")
                                    .put("event_version", 1)
                                    .put("occurred_at", "2026-09-30T00:00:00Z")
                                    .set("data", JsonNodeFactory.instance.objectNode()
                                            .put("alertId", "synthetic-alert"))));

            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(
                    consumer, TOPIC, Duration.ofSeconds(5));
            assertThat(record.key()).isEqualTo("70000000-0000-4000-8000-000000000001");
            assertThat(record.value()).contains("\"event_type\":\"EVENT_ALERT\"")
                    .contains("\"action\":\"GENERATED\"")
                    .contains("\"source_service\":\"EVENT_ALERT_SERVICE\"");
        } finally {
            producerFactory.destroy();
            broker.destroy();
        }
    }
}
