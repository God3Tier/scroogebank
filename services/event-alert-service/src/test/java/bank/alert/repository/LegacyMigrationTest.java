package bank.alert.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V10 is additive (ADR-0066): a database that already holds fixture-era alerts migrates, keeps every
 * row, and still shows the same exposure for them. Also migrates from empty.
 */
@Testcontainers
class LegacyMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(target).cleanDisabled(false).load();
    }

    @Test
    void legacyAlertsKeepTheirMeaningAndAnEmptyDatabaseMigrates() {
        Flyway before = flyway("9");
        before.clean();
        before.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        var instruments = jdbc.queryForList("SELECT id FROM instrument ORDER BY id LIMIT 2", String.class);
        UUID run = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID affected = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID alert = UUID.randomUUID();
        jdbc.update("INSERT INTO search_run (id, window_from, window_to, status, created_at) VALUES (?, now() - interval '4 hours', now(), 'SUCCEEDED', now())", run);
        jdbc.update("""
                INSERT INTO source_result (id, search_run_id, canonical_url, canonical_url_hash, title, source_domain, retrieved_at, expires_at)
                VALUES (?, ?, 'https://example.invalid/a', repeat('a', 64), 'Legacy event', 'example.invalid', now(), now() + interval '90 days')
                """, source, run);
        jdbc.update("""
                INSERT INTO public_event (id, source_result_id, classification, direction, confidence, classifier_version, occurred_at, created_at)
                VALUES (?, ?, 'EARNINGS', 'POSITIVE', 0.9, 'legacy-v1', now(), now())
                """, event, source);
        // The first fixture client holds two securities valued together: 10 + 90.
        String insertHolding = """
                INSERT INTO holding_snapshot (id, client_ref, account_ref, assigned_agent_subject, instrument_id, quantity, unit_price,
                    valuation_currency, fx_to_sgd, value_sgd, valued_at, freshness, source_version)
                VALUES (?, 'syn-client-001', 'syn-account-001', 'syn-agent-001', ?, ?, 1, 'SGD', 1, ?, '2026-10-01T00:00:00Z', 'FRESH', 'synthetic-holdings-v1')
                """;
        jdbc.update(insertHolding, affected, instruments.get(0), new BigDecimal("10"), new BigDecimal("10"));
        jdbc.update(insertHolding, other, instruments.get(1), new BigDecimal("90"), new BigDecimal("90"));
        jdbc.update("""
                INSERT INTO alert (id, client_ref, assigned_agent_subject, public_event_id, instrument_id, holding_snapshot_id, score, rule_version, status, created_at)
                VALUES (?, 'syn-client-001', 'syn-agent-001', ?, ?, ?, 80, 'rules-v1', 'NEW', now())
                """, alert, event, instruments.get(0), affected);

        flyway("latest").migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holding_snapshot", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM synthetic_portfolio", Long.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holding_snapshot WHERE portfolio_snapshot_id IS NOT NULL", Long.class)).isZero();
        // The legacy detail still groups by client, source version and valuation time: 10 of 100.
        BigDecimal percentage = new AlertListRepository(jdbc).findVisibleDetail(alert, "syn-agent-001", false)
                .orElseThrow().portfolioPercentage();
        assertThat(percentage).isEqualByComparingTo("10.0000");
        // The fixture-only foreign key is gone, so a CRM-linked client can be snapshotted.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                 WHERE table_name = 'holding_snapshot' AND constraint_type = 'FOREIGN KEY' AND constraint_name LIKE '%client_ref%'
                """, Long.class)).isZero();

        // And from empty, in one go.
        Flyway fresh = flyway("latest");
        fresh.clean();
        assertThat(fresh.migrate().migrations).hasSizeGreaterThanOrEqualTo(10);
    }
}
