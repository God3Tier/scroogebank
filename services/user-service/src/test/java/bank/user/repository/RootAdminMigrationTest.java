package bank.user.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RootAdminMigrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void upgradesExistingNonDeletableAdminWithoutChangingItsIdentity() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("1")).load().migrate();

        UUID id = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users (id, first_name, last_name, email, role, enabled, deletable, idp_sub, created_at, updated_at) VALUES ('"
                    + id + "', 'Root', 'Admin', 'root@example.test', 'ADMIN', TRUE, FALSE, 'root-sub', NOW(), NOW())");
        }

        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT id, role, deletable, idp_sub FROM users WHERE email = 'root@example.test'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getObject("id").toString()).isEqualTo(id.toString());
            assertThat(row.getString("role")).isEqualTo("ROOT_ADMIN");
            assertThat(row.getBoolean("deletable")).isFalse();
            assertThat(row.getString("idp_sub")).isEqualTo("root-sub");
        }
    }
}
