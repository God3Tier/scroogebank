package bank.dashboard.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class AuditSqsClientConfiguration {

    /** Credentials come from the task role, and are looked up only on a send. */
    @Bean
    public SqsClient auditSqsClient(@Value("${audit.sqs.region}") String region) {
        return SqsClient.builder().region(Region.of(region)).build();
    }
}
