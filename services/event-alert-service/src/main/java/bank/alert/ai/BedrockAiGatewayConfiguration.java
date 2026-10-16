package bank.alert.ai;

import bank.alert.config.AiProperties;
import bank.alert.config.BedrockAiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Configuration
@ConditionalOnProperty(prefix = "alert.ai", name = "provider", havingValue = "bedrock")
public class BedrockAiGatewayConfiguration {
    @Bean(destroyMethod = "close")
    BedrockRuntimeClient bedrockRuntimeClient(BedrockAiProperties properties) {
        return BedrockRuntimeClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(properties.region()))
                .build();
    }

    @Bean
    AiModelGateway bedrockAiModelGateway(BedrockRuntimeClient client, AiProperties properties) {
        return new BedrockAiModelGateway(client, properties);
    }
}
