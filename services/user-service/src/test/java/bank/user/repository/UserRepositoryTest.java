package bank.user.repository;

import bank.user.domain.Role;
import bank.user.domain.User;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private UserRepository userRepository;

    @Test
    void savesAndFindsUserByEmail() {
        User user = new User(UUID.randomUUID(), "Jane", "Tan", "jane.tan@scroogebank.com", Role.AGENT, "idp-sub-1");
        userRepository.save(user);

        assertThat(userRepository.findByEmail("jane.tan@scroogebank.com")).isPresent();
        assertThat(userRepository.existsByEmail("jane.tan@scroogebank.com")).isTrue();
    }

    @Test
    void rejectsDuplicateEmail() {
        userRepository.save(new User(UUID.randomUUID(), "A", "One", "dup@scroogebank.com", Role.AGENT, "idp-sub-2"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(
                new User(UUID.randomUUID(), "B", "Two", "dup@scroogebank.com", Role.AGENT, "idp-sub-3")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsUserByIdpSub() {
        User user = new User(UUID.randomUUID(), "Root", "Admin", "admin@scroogebank.com", Role.ADMIN, "idp-sub-root");
        userRepository.save(user);

        assertThat(userRepository.findByIdpSub("idp-sub-root")).isPresent();
    }
}
