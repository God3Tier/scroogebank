package bank.clienthealth.task;

import bank.clienthealth.task.application.BoundedDispositionPolicy;
import bank.clienthealth.task.application.RelationshipTaskQueryService;
import bank.clienthealth.task.application.RelationshipTaskService;
import bank.clienthealth.task.assignment.ClientCredentialsTokenProvider;
import bank.clienthealth.task.assignment.HttpClientAssignmentAdapter;
import bank.clienthealth.task.persistence.JdbcRelationshipTaskRepository;
import bank.clienthealth.task.port.ClientAssignmentPort;
import bank.clienthealth.task.port.DispositionPolicy;
import bank.clienthealth.explanation.ExplanationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class TaskRuntimeConfiguration {
    @Bean Clock taskClock() { return Clock.systemUTC(); }

    @Bean
    RestClient clientAssignmentRestClient(RestClient.Builder builder) {
        Duration timeout = Duration.ofSeconds(3);
        return builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(timeout).withReadTimeout(timeout))).build();
    }

    @Bean
    ClientCredentialsTokenProvider clientAssignmentTokens(
            RestClient clientAssignmentRestClient,
            Clock taskClock,
            @Value("${client-health.assignment.oauth.token-uri:}") String tokenUri,
            @Value("${client-health.assignment.oauth.client-id:}") String clientId,
            @Value("${client-health.assignment.oauth.client-secret:}") String clientSecret,
            @Value("${client-health.assignment.oauth.scope:}") String scope
    ) {
        return new ClientCredentialsTokenProvider(
                clientAssignmentRestClient, taskClock, tokenUri, clientId, clientSecret, scope);
    }

    @Bean
    ClientAssignmentPort clientAssignmentPort(
            RestClient clientAssignmentRestClient,
            ClientCredentialsTokenProvider clientAssignmentTokens,
            @Value("${client-health.assignment.base-url:}") String baseUrl
    ) {
        return new HttpClientAssignmentAdapter(clientAssignmentRestClient, clientAssignmentTokens, baseUrl);
    }

    @Bean DispositionPolicy dispositionPolicy() { return new BoundedDispositionPolicy(); }

    @Bean
    JdbcRelationshipTaskRepository relationshipTaskRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ObjectMapper json
    ) {
        return new JdbcRelationshipTaskRepository(jdbc, new TransactionTemplate(transactionManager), json);
    }

    @Bean
    RelationshipTaskService relationshipTaskService(
            JdbcRelationshipTaskRepository repository,
            ClientAssignmentPort assignments,
            DispositionPolicy dispositionPolicy,
            Clock taskClock
    ) {
        return new RelationshipTaskService(repository, assignments, dispositionPolicy,
                taskClock, () -> UUID.randomUUID().toString());
    }

    @Bean
    RelationshipTaskQueryService relationshipTaskQueryService(
            JdbcRelationshipTaskRepository repository, ClientAssignmentPort assignments,
            ExplanationRepository explanations
    ) {
        return new RelationshipTaskQueryService(repository, assignments, explanations);
    }
}
