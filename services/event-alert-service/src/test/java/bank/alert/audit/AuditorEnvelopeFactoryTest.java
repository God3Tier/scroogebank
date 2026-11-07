package bank.alert.audit;

import bank.alert.domain.AuditOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditorEnvelopeFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AuditorEnvelopeFactory factory = new AuditorEnvelopeFactory(mapper);

    @Test
    void mapsOutboxEventToAuditorCompatibleRedactedEnvelope() throws Exception {
        UUID eventId = UUID.fromString("70000000-0000-4000-8000-000000000001");
        AuditOutbox event = new AuditOutbox(eventId, "alert.decision-recorded", 1,
                "{\"actorSubject\":\"synthetic-agent\",\"alertId\":\"synthetic-alert\"}",
                Instant.parse("2026-09-30T00:00:00Z"));

        AuditorEnvelope envelope = factory.create(event);
        String json = mapper.writeValueAsString(envelope);

        assertThat(envelope.eventId()).isEqualTo(eventId.toString());
        assertThat(envelope.eventType()).isEqualTo("EVENT_ALERT");
        assertThat(envelope.action()).isEqualTo("DECISION_RECORDED");
        assertThat(envelope.sourceService()).isEqualTo("EVENT_ALERT_SERVICE");
        assertThat(envelope.actorId()).isEqualTo("synthetic-agent");
        assertThat(envelope.payload().path("event_id").asText()).isEqualTo(eventId.toString());
        assertThat(envelope.payload().path("data").path("alertId").asText())
                .isEqualTo("synthetic-alert");
        assertThat(json).contains("\"event_id\":\"" + eventId + "\"")
                .contains("\"event_type\":\"EVENT_ALERT\"")
                .contains("\"source_service\":\"EVENT_ALERT_SERVICE\"")
                .doesNotContain("eventType", "sourceService");
    }
}
