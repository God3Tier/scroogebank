package bank.engagement.drafting;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Configuration
public class DraftModelConfiguration {

    /** Bedrock only when the kill switch is on; no client is built otherwise. */
    @Bean
    @ConditionalOnMissingBean(DraftModelGateway.class)
    @ConditionalOnProperty(prefix = "engagement.ai", name = "enabled", havingValue = "true")
    DraftModelGateway bedrockDraftModelGateway(AiProperties properties) {
        BedrockRuntimeClient client = BedrockRuntimeClient.builder()
                .region(Region.of(properties.region()))
                .build();
        return new BedrockDraftModelGateway(client, properties);
    }
}
