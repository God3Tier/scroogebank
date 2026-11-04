package bank.alert.repository;

import bank.alert.domain.SourceSearchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SourceSearchRequestRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private SourceSearchRequestRepository repository;

    private SourceSearchRequest request(String instrumentId, Instant at, String status) {
        return new SourceSearchRequest(UUID.randomUUID(), instrumentId, "example.com", "agent-a", at, status, null);
    }

    @Test
    void onlyAStartedSearchInsideTheCooldownCounts() {
        repository.saveAndFlush(request("SGX-D05", NOW.minusSeconds(60), "STARTED"));
        repository.saveAndFlush(request("SGX-O39", NOW.minusSeconds(30), "COOLDOWN"));
        repository.saveAndFlush(request("SGX-Z74", NOW.minusSeconds(3600), "STARTED"));

        assertThat(repository.existsByInstrumentIdAndStatusAndRequestedAtAfter("SGX-D05", "STARTED", NOW.minusSeconds(900))).isTrue();
        assertThat(repository.existsByInstrumentIdAndStatusAndRequestedAtAfter("SGX-O39", "STARTED", NOW.minusSeconds(900))).isFalse();
        assertThat(repository.existsByInstrumentIdAndStatusAndRequestedAtAfter("SGX-Z74", "STARTED", NOW.minusSeconds(900))).isFalse();
    }

    @Test
    void theDailyCountIgnoresRefusedAndOldSearches() {
        repository.saveAndFlush(request("SGX-D05", NOW.minusSeconds(100), "STARTED"));
        repository.saveAndFlush(request("SGX-O39", NOW.minusSeconds(200), "STARTED"));
        repository.saveAndFlush(request("SGX-Z74", NOW.minusSeconds(300), "DAILY_LIMIT"));
        repository.saveAndFlush(request("SGX-C6L", NOW.minusSeconds(90000), "STARTED"));

        assertThat(repository.countByStatusAndRequestedAtAfter("STARTED", NOW.minusSeconds(86400))).isEqualTo(2);
    }

    @Test
    void anUnknownStatusIsRefusedByTheDatabase() {
        assertThatThrownBy(() -> repository.saveAndFlush(request("SGX-D05", NOW, "WHATEVER")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
