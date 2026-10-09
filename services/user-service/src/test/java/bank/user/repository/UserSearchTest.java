package bank.user.repository;

import bank.user.domain.Role;
import bank.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs UserRepository.search against a real Postgres so the filters are checked in SQL. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserSearchTest {

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

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        userRepository.save(user("Root", "Admin", Role.ADMIN, true));
        userRepository.save(user("Bob", "Brown", Role.AGENT, true));
        userRepository.save(user("Amy", "Brown", Role.AGENT, true));
        userRepository.save(user("Carl", "Cole", Role.AGENT, false));
        userRepository.flush();
    }

    @Test
    void listsEveryoneSortedByLastThenFirstName() {
        Page<User> page = userRepository.search(null, null, 0, 20);

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).extracting(User::getFirstName)
                .containsExactly("Root", "Amy", "Bob", "Carl");
    }

    @Test
    void filtersByRole() {
        assertThat(userRepository.search(Role.AGENT, null, 0, 20).getContent())
                .extracting(User::getRole).containsOnly(Role.AGENT).hasSize(3);
    }

    @Test
    void filtersByRoleAndEnabled() {
        assertThat(userRepository.search(Role.AGENT, false, 0, 20).getContent())
                .extracting(User::getFirstName).containsExactly("Carl");
    }

    @Test
    void pagesThroughResults() {
        Page<User> second = userRepository.search(null, null, 1, 3);

        assertThat(second.getTotalPages()).isEqualTo(2);
        assertThat(second.getContent()).extracting(User::getFirstName).containsExactly("Carl");
    }

    private static User user(String first, String last, Role role, boolean enabled) {
        User user = new User(UUID.randomUUID(), first, last,
                first.toLowerCase() + "." + last.toLowerCase() + "@scroogebank.com", role, "sub-" + UUID.randomUUID());
        user.setEnabled(enabled);
        return user;
    }
}
