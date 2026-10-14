package bank.alert.worker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the whole service in WORKER mode with ingestion switched on but no
 * search provider configured, as before the AgentCore client exists. The run
 * must fail cleanly: recorded as failed, nothing invented, exit code 1. The
 * API stays off (ADR-0007).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "alert.mode=WORKER",
        "alert.ingestion.enabled=true",
        "alert.operations.ingestion-enabled=true"})
@Testcontainers
class WorkerApplicationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApplicationContext context;
    @Autowired WorkerRunner runner;
    @Autowired JdbcTemplate jdbc;

    @Test
    void runsOneWindowAtStartupAndExitsNonZeroWhenTheSearchFails() {
        assertThat(jdbc.queryForList("SELECT status, error_code FROM search_run"))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.get("status")).isEqualTo("FAILED");
                    assertThat(run.get("error_code")).isEqualTo("PROVIDER_NOT_CONFIGURED");
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_result", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert", Long.class)).isZero();
        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    void servesNoApiAndStartsNoTimers() {
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }
}
