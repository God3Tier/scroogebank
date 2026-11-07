package bank.alert.worker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the whole service in WORKER mode with an operator replay request
 * (ADR-0049). Ingestion is on but no search provider is configured, so the
 * replay run itself fails cleanly; what matters here is that the audit event is
 * written first, with the operator's identity, and that the scheduler watermark
 * does not move.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "alert.mode=WORKER",
        "alert.ingestion.enabled=true",
        "alert.operations.ingestion-enabled=true",
        "alert.worker.replay.operator=operator-1",
        "alert.worker.replay.correlation-id=ticket-42"})
@Testcontainers
class WorkerReplayApplicationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        Instant hourAgo = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        registry.add("alert.worker.replay.from", () -> hourAgo.minus(Duration.ofHours(2)).toString());
        registry.add("alert.worker.replay.to", hourAgo::toString);
    }

    @Autowired WorkerRunner runner;
    @Autowired JdbcTemplate jdbc;

    @Test
    void auditsTheReplayBeforeSearchingAndLeavesTheWatermarkAlone() {
        assertThat(jdbc.queryForList("SELECT event_type, payload::text AS payload FROM audit_outbox"
                + " WHERE event_type = 'alert.replay-requested'"))
                .singleElement()
                .satisfies(row -> {
                    assertThat((String) row.get("payload"))
                            .contains("\"actorSubject\": \"operator-1\"")
                            .contains("\"correlationId\": \"ticket-42\"")
                            .contains("REPLAY_REQUESTED");
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM search_run", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processing_watermark", Long.class)).isZero();
        assertThat(runner.getExitCode()).isEqualTo(1);
    }
}
