package bank.user.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the audit_outbox table and the native locking query against a real Postgres. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AuditOutboxRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AuditOutboxRepository outbox;

    @Test
    void locksOldestEntriesFirst() {
        AuditOutboxEntry newer = new AuditOutboxEntry(UUID.randomUUID(), "{\"n\":2}", Instant.parse("2026-10-09T06:00:02Z"));
        AuditOutboxEntry older = new AuditOutboxEntry(UUID.randomUUID(), "{\"n\":1}", Instant.parse("2026-10-09T06:00:01Z"));
        outbox.saveAndFlush(newer);
        outbox.saveAndFlush(older);

        assertThat(outbox.lockOldest(1)).extracting(AuditOutboxEntry::getEventId).containsExactly(older.getEventId());
        assertThat(outbox.lockOldest(10)).hasSize(2);
    }
}
