package bank.engagement.integration.identity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

import java.net.URI;
import java.util.Optional;

@Configuration
@Slf4j
public class IdentityConfiguration {

    /**
     * Cognito when a user pool is configured. Without one no agent can be found,
     * so mail to agents fails with {@code agent_email_unavailable}.
     */
    @Bean
    @ConditionalOnMissingBean
    AgentDirectory agentDirectory(IdentityProperties properties) {
        if (properties.userPoolId() == null || properties.userPoolId().isBlank()) {
            log.warn("COGNITO_USER_POOL_ID is not set: agents cannot be looked up and no mail reaches them");
            return sub -> Optional.empty();
        }
        var builder = CognitoIdentityProviderClient.builder().region(Region.of(properties.region()));
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return new CognitoAgentDirectory(builder.build(), properties);
    }
}
