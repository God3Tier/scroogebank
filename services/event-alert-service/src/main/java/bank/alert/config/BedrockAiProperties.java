package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "alert.ai.bedrock")
public record BedrockAiProperties(String region) {
    public BedrockAiProperties {
        region = region == null || region.isBlank() ? "ap-southeast-1" : region;
    }
}
