package bank.engagement.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

@Configuration
@ConditionalOnProperty(prefix = "engagement.audit.publisher", name = "enabled", havingValue = "true")
public class SqsAuditClientConfiguration {

    @Bean
    public SqsClient engagementAuditSqsClient(
            @Value("${engagement.audit.publisher.region}") String region,
            @Value("${engagement.audit.publisher.endpoint:}") String endpoint) {
        var builder = SqsClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
