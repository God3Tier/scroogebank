package bank.clienthealth.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import bank.clienthealth.metrics.ClientHealthMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AuditDeliveryConfiguration {
    @Bean
    JdbcAuditOutboxRepository auditOutboxRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json,
            ClientHealthMetrics metrics
    ) {
        return new JdbcAuditOutboxRepository(
                jdbc, new TransactionTemplate(transactionManager), json,
                () -> UUID.randomUUID().toString(), metrics);
    }

    @Bean
    AuditBacklogMetrics auditBacklogMetrics(
            JdbcAuditOutboxRepository repository, Clock clock, MeterRegistry registry) {
        return new AuditBacklogMetrics(repository, clock, registry);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            name = "client-health.audit.publisher-enabled",
            havingValue = "true")
    SqsClient clientHealthAuditSqsClient(
            @Value("${client-health.audit.region}") String region,
            @Value("${client-health.audit.endpoint:}") String endpoint
    ) {
        var builder = SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build());
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "client-health.audit.publisher-enabled",
            havingValue = "true")
    SqsAuditDeliveryGateway auditDeliveryGateway(
            SqsClient clientHealthAuditSqsClient,
            ObjectMapper json,
            @Value("${client-health.audit.queue-url}") String queueUrl
    ) {
        return new SqsAuditDeliveryGateway(clientHealthAuditSqsClient, json, queueUrl);
    }

    @Bean
    @ConditionalOnProperty(
            name = "client-health.audit.publisher-enabled",
            havingValue = "true")
    AuditOutboxRelay auditOutboxRelay(
            JdbcAuditOutboxRepository repository,
            SqsAuditDeliveryGateway gateway,
            ClientHealthMetrics metrics
    ) {
        return new AuditOutboxRelay(repository, gateway, metrics);
    }

    @Bean
    @ConditionalOnProperty(
            name = "client-health.audit.publisher-enabled",
            havingValue = "true")
    AuditRelayJob auditRelayJob(
            AuditOutboxRelay relay,
            Clock clock,
            @Value("${client-health.audit.batch-size:50}") int batchSize,
            @Value("${client-health.audit.lease-seconds:60}") long leaseSeconds
    ) {
        return new AuditRelayJob(
                relay, clock, batchSize, Duration.ofSeconds(leaseSeconds));
    }
}
