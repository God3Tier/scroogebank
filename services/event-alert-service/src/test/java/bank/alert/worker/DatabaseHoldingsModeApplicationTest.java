package bank.alert.worker;

import bank.alert.crm.CrmDirectory;
import bank.alert.holdings.DatabaseHoldingsProvider;
import bank.alert.holdings.DatabaseSearchUniverseProvider;
import bank.alert.holdings.SyntheticHoldingsProvider;
import bank.alert.holdings.SyntheticHoldingsSearchUniverseProvider;
import bank.alert.portfolio.PortfolioController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database mode swaps the fixture providers for the maintained portfolios and has no
 * path back to fixture data. The process starts against the real migrations.
 */
@SpringBootTest(properties = {
        "alert.mode=API",
        "alert.holdings.mode=database",
        "alert.crm.account-directory-url=https://crm.internal.invalid/internal/alerts/accounts/lookup",
        "alert.crm.token-endpoint=https://idp.invalid/oauth2/token",
        "alert.crm.client-id=alert-directory",
        "alert.crm.client-secret=not-a-real-secret",
        "alert.crm.scope=crm-accounts/alert-directory"})
@Testcontainers
class DatabaseHoldingsModeApplicationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApplicationContext context;

    @Test
    void usesMaintainedPortfoliosAndNeverTheFixture() {
        assertThat(context.getBeansOfType(DatabaseHoldingsProvider.class)).hasSize(1);
        assertThat(context.getBeansOfType(DatabaseSearchUniverseProvider.class)).hasSize(1);
        assertThat(context.getBeansOfType(SyntheticHoldingsProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(SyntheticHoldingsSearchUniverseProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(CrmDirectory.class)).hasSize(1);
        assertThat(context.getBeansOfType(PortfolioController.class)).hasSize(1);
    }
}
