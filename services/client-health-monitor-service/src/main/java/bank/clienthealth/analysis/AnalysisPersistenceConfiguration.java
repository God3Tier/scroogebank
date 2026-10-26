package bank.clienthealth.analysis;

import bank.clienthealth.activity.application.ActivityBaselineService;
import bank.clienthealth.activity.application.ActivityProjectionService;
import bank.clienthealth.activity.persistence.JdbcActivityProjectionRepository;
import bank.clienthealth.analysis.persistence.JdbcAnalysisRunRepository;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class AnalysisPersistenceConfiguration {
    @Bean
    JdbcActivityProjectionRepository activityProjectionRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        return new JdbcActivityProjectionRepository(
                jdbc, new TransactionTemplate(transactionManager));
    }

    @Bean
    ActivityProjectionService activityProjectionService(
            JdbcActivityProjectionRepository repository
    ) {
        return new ActivityProjectionService(repository);
    }

    @Bean
    ActivityBaselineService activityBaselineService(
            JdbcActivityProjectionRepository repository
    ) {
        return new ActivityBaselineService(repository);
    }

    @Bean
    JdbcAnalysisRunRepository analysisRunRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        return new JdbcAnalysisRunRepository(
                jdbc, new TransactionTemplate(transactionManager),
                () -> UUID.randomUUID().toString());
    }
}
