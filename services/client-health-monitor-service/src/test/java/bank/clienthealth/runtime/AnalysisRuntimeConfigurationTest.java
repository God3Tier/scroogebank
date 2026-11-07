package bank.clienthealth.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import bank.clienthealth.activity.application.ActivityBaselineService;
import bank.clienthealth.activity.application.ActivityProjectionService;
import bank.clienthealth.analysis.application.RelationshipHealthAnalysisOrchestrator;
import bank.clienthealth.analysis.port.AnalysisRunPort;
import bank.clienthealth.contract.RelationshipTaskPort;
import bank.clienthealth.metrics.ClientHealthMetrics;
import bank.clienthealth.transaction.adapter.http.HttpTransactionReplaySource;
import bank.clienthealth.transaction.adapter.http.TransactionReplayHttpConfiguration;
import bank.clienthealth.transaction.application.TransactionReplayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

class AnalysisRuntimeConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestDependencies.class,
                    TransactionReplayHttpConfiguration.class,
                    ClientHealthRuntimeConfiguration.class);

    @Test
    void analysisModeWiresTheProductionReplayPipeline() {
        contextRunner.withPropertyValues(
                "client-health.runtime.mode=ANALYSIS",
                "client-health.runtime.lease-duration=60m",
                "client-health.transaction-replay.endpoint="
                        + "https://transactions.test/internal/v1/transactions",
                "client-health.transaction-replay.token-uri=https://idp.test/oauth2/token",
                "client-health.transaction-replay.client-id=health-client",
                "client-health.transaction-replay.client-secret=synthetic-secret",
                "client-health.transaction-replay.scope=crm-transactions/read",
                "client-health.transaction-replay.connect-timeout=1s",
                "client-health.transaction-replay.read-timeout=2s",
                "client-health.transaction-replay.page-size=100",
                "client-health.transaction-replay.maximum-pages=10",
                "client-health.transaction-replay.maximum-rows=1000"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(HttpTransactionReplaySource.class);
            assertThat(context).hasSingleBean(TransactionReplayService.class);
            assertThat(context).hasSingleBean(RelationshipHealthAnalysisOrchestrator.class);
            assertThat(context).hasSingleBean(AnalysisRunner.class);
        });
    }

    @Test
    void apiModeDoesNotRequireReplayCredentialsOrCreateAnalysisBeans() {
        contextRunner.withPropertyValues(
                "client-health.runtime.mode=API",
                "client-health.runtime.lease-duration=60m"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(HttpTransactionReplaySource.class);
            assertThat(context).doesNotHaveBean(TransactionReplayService.class);
            assertThat(context).doesNotHaveBean(RelationshipHealthAnalysisOrchestrator.class);
            assertThat(context).doesNotHaveBean(AnalysisRunner.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean RestClient.Builder restClientBuilder() { return RestClient.builder(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean ClientHealthMetrics metrics() { return ClientHealthMetrics.noOp(); }
        @Bean ActivityProjectionService projection() { return mock(ActivityProjectionService.class); }
        @Bean ActivityBaselineService baseline() { return mock(ActivityBaselineService.class); }
        @Bean RelationshipTaskPort tasks() { return mock(RelationshipTaskPort.class); }
        @Bean AnalysisRunPort runs() { return mock(AnalysisRunPort.class); }
    }
}
