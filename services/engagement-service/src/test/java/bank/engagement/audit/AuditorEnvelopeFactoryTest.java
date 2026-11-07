package bank.engagement.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditorEnvelopeFactoryTest {

    private final AuditorEnvelopeFactory factory = new AuditorEnvelopeFactory(new ObjectMapper());

    @Test
    void wrapsTheRowInTheEnvelopeTheAuditorValidates() {
        UUID id = UUID.fromString("80000000-0000-4000-8000-000000000001");
        AuditOutbox row = new AuditOutbox(id, AuditAction.EVENT_CREATE.outboxType(), 1,
                "{\"calendarEventId\":\"e-1\",\"actorId\":\"agent-sub\"}", Instant.parse("2026-10-21T02:00:00Z"));

        AuditorEnvelope envelope = factory.create(row);

        assertThat(envelope.eventId()).isEqualTo(id.toString());
        assertThat(envelope.eventType()).isEqualTo("ENGAGEMENT");
        assertThat(envelope.sourceService()).isEqualTo("ENGAGEMENT");
        assertThat(envelope.action()).isEqualTo("EVENT_CREATE");
        assertThat(envelope.actorId()).isEqualTo("agent-sub");
        assertThat(envelope.payload().path("event_id").asText()).isEqualTo(id.toString());
        assertThat(envelope.payload().path("event_version").asInt()).isEqualTo(1);
        assertThat(envelope.payload().path("occurred_at").asText()).isEqualTo("2026-10-21T02:00:00Z");
        assertThat(envelope.payload().path("data").path("calendarEventId").asText()).isEqualTo("e-1");
        assertThat(envelope.payload().path("data").has("actorId")).isFalse();
    }

    @Test
    void everyActionMapsToAnOutboxTypeAndBack() {
        for (AuditAction action : AuditAction.values()) {
            assertThat(AuditAction.fromOutboxType(action.outboxType())).isEqualTo(action);
        }
        assertThat(AuditAction.REMINDER_MISSED.outboxType()).isEqualTo("engagement.reminder-missed");
        assertThatThrownBy(() -> AuditAction.fromOutboxType("alert.generated"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
