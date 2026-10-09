package bank.user.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

// Scheduling runs AuditOutboxRelay.
@Configuration
@EnableScheduling
public class AuditSqsClientConfiguration {

    @Bean
    public SqsClient auditSqsClient(@Value("${idp.cognito.region}") String region) {
        return SqsClient.builder().region(Region.of(region)).build();
    }
}
