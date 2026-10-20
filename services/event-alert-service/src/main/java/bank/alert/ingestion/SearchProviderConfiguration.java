package bank.alert.ingestion;

import bank.alert.config.AgentCoreProperties;
import bank.alert.config.IngestionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import java.time.Clock;

@Configuration
public class SearchProviderConfiguration {

    /**
     * The IAM-signed Gateway client exists only when a gateway URL is configured;
     * the credentials come from the AWS default chain (the ECS task role).
     */
    @Bean
    @ConditionalOnExpression("'${alert.ingestion.agentcore.gateway-url:}' != ''")
    AgentCoreGatewayClient agentCoreGatewayClient(AgentCoreProperties properties, ObjectMapper mapper,
            Clock clock) {
        return new HttpAgentCoreGatewayClient(properties, mapper,
                DefaultCredentialsProvider.builder().build(), clock);
    }

    @Bean
    @ConditionalOnMissingBean(PublicSearchProvider.class)
    PublicSearchProvider publicSearchProvider(ObjectProvider<AgentCoreGatewayClient> client,
            IngestionProperties properties) {
        AgentCoreGatewayClient gateway = client.getIfAvailable();
        if (gateway != null) {
            return new AgentCoreSearchProvider(gateway, properties.approvedDomains());
        }
        return request -> {
            throw new SearchProviderException("PROVIDER_NOT_CONFIGURED", false,
                    "no approved public-search provider client is configured");
        };
    }
}
