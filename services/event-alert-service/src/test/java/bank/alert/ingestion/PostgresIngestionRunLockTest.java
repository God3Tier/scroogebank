package bank.alert.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PostgresIngestionRunLock.class)
class PostgresIngestionRunLockTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired PostgresIngestionRunLock lock;
    @Autowired DataSource dataSource;

    @Test
    void onlyOneRunHoldsTheLockAtATime() {
        Optional<IngestionRunLock.Hold> first = lock.tryAcquire();
        assertThat(first).isPresent();

        assertThat(lock.tryAcquire()).isEmpty();

        first.get().close();
        Optional<IngestionRunLock.Hold> second = lock.tryAcquire();
        assertThat(second).isPresent();
        second.get().close();
    }

    @Test
    void releasingLeavesNoAdvisoryLockOnThePooledConnection() throws Exception {
        lock.tryAcquire().orElseThrow().close();

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet held = statement.executeQuery(
                        "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'")) {
            held.next();
            assertThat(held.getInt(1)).isZero();
        }
    }
}
