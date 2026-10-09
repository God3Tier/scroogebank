package bank.alert.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiGatewayConfiguration {
    @Bean
    @ConditionalOnMissingBean(AiModelGateway.class)
    AiModelGateway unconfiguredAiModelGateway() {
        return (request, timeout) -> { throw new AiProviderException(
                "PROVIDER_NOT_CONFIGURED", false, "no approved AI model gateway is configured"); };
    }
}
