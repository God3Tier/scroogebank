package bank.alert.ai;

import bank.alert.config.AiProperties;
import bank.alert.config.AnthropicAiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;
import java.time.Duration;

/** Uses the Anthropic API as the model when {@code alert.ai.provider=anthropic}: developer machines only (ADR-0083). */
@Configuration
@ConditionalOnProperty(prefix = "alert.ai", name = "provider", havingValue = "anthropic")
public class AnthropicAiGatewayConfiguration {

    @Bean
    @Primary
    AiModelGateway anthropicAiModelGateway(AiProperties properties, AnthropicAiProperties anthropic) {
        if (!anthropic.hasKey()) {
            throw new IllegalStateException("ALERT_ANTHROPIC_API_KEY is required when alert.ai.provider=anthropic");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new AnthropicAiModelGateway(http, properties, anthropic);
    }
}
