package bank.alert.repository;

import bank.alert.domain.InstrumentSourceDomain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class InstrumentSourceDomainRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-04T04:00:00Z");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private InstrumentSourceDomainRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private InstrumentSourceDomain entry(String instrumentId, String domain) {
        return new InstrumentSourceDomain(UUID.randomUUID(), instrumentId, domain, "agent-a", NOW);
    }

    @Test
    void savesAndListsActiveDomainsPerCompanyInTheOrderAdded() {
        repository.saveAndFlush(new InstrumentSourceDomain(UUID.randomUUID(), "SGX-D05", "b.example.com", "agent-a", NOW.plusSeconds(5)));
        repository.saveAndFlush(entry("SGX-D05", "a.example.com"));
        repository.saveAndFlush(entry("SGX-O39", "ocbc-news.example.com"));

        assertThat(repository.findByInstrumentIdAndActiveTrueOrderByAddedAtAsc("SGX-D05"))
                .extracting(InstrumentSourceDomain::getDomain).containsExactly("a.example.com", "b.example.com");
        assertThat(repository.findByInstrumentIdInAndActiveTrueOrderByAddedAtAsc(List.of("SGX-D05", "SGX-O39"))).hasSize(3);
        assertThat(repository.countByInstrumentIdAndActiveTrue("SGX-D05")).isEqualTo(2);
    }

    @Test
    void anActiveDuplicateForTheSameCompanyIsRefusedButAnotherCompanyIsFine() {
        repository.saveAndFlush(entry("SGX-D05", "dup.example.com"));

        assertThatThrownBy(() -> repository.saveAndFlush(entry("SGX-D05", "dup.example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameDomainCanBeAddedForAnotherCompany() {
        repository.saveAndFlush(entry("SGX-D05", "shared.example.com"));
        repository.saveAndFlush(entry("SGX-O39", "shared.example.com"));

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void aDeactivatedDomainStaysAsHistoryAndCanBeAddedAgain() {
        InstrumentSourceDomain first = repository.saveAndFlush(entry("SGX-D05", "again.example.com"));
        first.deactivate("agent-b", NOW.plusSeconds(60));
        repository.saveAndFlush(first);

        assertThat(repository.findByInstrumentIdAndActiveTrueOrderByAddedAtAsc("SGX-D05")).isEmpty();
        assertThat(repository.findByIdAndInstrumentIdAndActiveTrue(first.getId(), "SGX-D05")).isEmpty();

        repository.saveAndFlush(entry("SGX-D05", "again.example.com"));

        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.countByInstrumentIdAndActiveTrue("SGX-D05")).isEqualTo(1);
    }

    @Test
    void theDatabaseItselfRefusesAnUpperCaseDomain() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO instrument_source_domain (id, instrument_id, domain, added_by_subject, added_at) VALUES (?, 'SGX-D05', 'UPPER.com', 'a', now())",
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseItselfRefusesAHalfFinishedDeactivation() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO instrument_source_domain (id, instrument_id, domain, added_by_subject, added_at, active) VALUES (?, 'SGX-D05', 'x.com', 'a', now(), false)",
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aCompanyThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> repository.saveAndFlush(entry("NO-SUCH", "x.example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
