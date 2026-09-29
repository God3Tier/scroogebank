package bank.alert.operations;

import bank.alert.config.OperationalProperties;
import bank.alert.domain.AuditOutbox;
import bank.alert.repository.AuditOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({OutboxOperationalControlAuditSink.class,
        OutboxOperationalControlAuditSinkTest.TestConfiguration.class})
@TestPropertySource(properties = "alert.audit.max-pending=1")
class OutboxOperationalControlAuditSinkTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-29T02:00:00Z"), ZoneOffset.UTC);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private OutboxOperationalControlAuditSink auditSink;

    @Autowired
    private AuditOutboxRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void reenablePersistsRedactedAuditBeforeChangingControlState() throws Exception {
        OutboundControlService controls = controls();
        controls.disable(OutboundCapability.INGESTION);

        controls.reenable(OutboundCapability.INGESTION, "operator-subject", "corr-control-001");

        assertThat(controls.status(OutboundCapability.INGESTION).enabled()).isTrue();
        AuditOutbox outbox = outboxRepository.findAll().getFirst();
        assertThat(outbox.getEventType()).isEqualTo("alert.operational-control-changed");
        JsonNode payload = objectMapper.readTree(outbox.getPayload());
        assertThat(payload.path("capability").asText()).isEqualTo("INGESTION");
        assertThat(payload.path("action").asText()).isEqualTo("REENABLED");
        assertThat(payload.path("actorSubject").asText()).isEqualTo("operator-subject");
        assertThat(payload.path("actorRole").asText()).isEqualTo("OPERATOR");
        assertThat(payload.path("correlationId").asText()).isEqualTo("corr-control-001");
        assertThat(payload.path("previousReason").asText()).isEqualTo("OPERATOR_DISABLED");
        assertThat(payload.path("newState").asText()).isEqualTo("ENABLED");
        assertThat(outbox.getPayload()).doesNotContain("client", "holding", "prompt", "token");
    }

    @Test
    void fullOutboxLeavesCapabilityDisabled() {
        outboxRepository.saveAndFlush(new AuditOutbox(
                UUID.randomUUID(), "synthetic.pending", 1, "{}", CLOCK.instant()));
        OutboundControlService controls = controls();
        controls.disable(OutboundCapability.MODEL);

        assertThatThrownBy(() -> controls.reenable(
                OutboundCapability.MODEL, "operator-subject", "corr-control-002"))
                .isInstanceOf(OperationalControlAuditUnavailableException.class)
                .hasMessageContaining("capacity");
        assertThat(controls.status(OutboundCapability.MODEL).enabled()).isFalse();
    }

    private OutboundControlService controls() {
        OperationalProperties properties = new OperationalProperties(
                true, true, 5, 4, 100, 60, BigDecimal.TEN);
        return new OutboundControlService(properties, auditSink,
                new OperationalMetrics(new SimpleMeterRegistry()), CLOCK);
    }

    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
