package bank.clienthealth.explanation;

import bank.clienthealth.contract.RelationshipTaskPort;
import bank.clienthealth.task.application.RelationshipTaskService;
import bank.clienthealth.task.port.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class ExplanationPersistenceConfiguration {
    @Bean
    ExplanationRepository explanationRepository(JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, ObjectMapper mapper) {
        return new JdbcExplanationRepository(jdbc, new TransactionTemplate(transactionManager), mapper);
    }

    @Bean @Primary
    RelationshipTaskPort explainingRelationshipTaskPort(RelationshipTaskService tasks,
            TaskRepository taskRepository, ExplanationRepository explanations,
            ExplanationProvider provider, Clock clock) {
        return new ExplainingRelationshipTaskPort(tasks, taskRepository, explanations, provider, clock);
    }
}
