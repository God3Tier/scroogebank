package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code alert.ai.anthropic.*}: the Anthropic Messages API, for developer machines (ADR-0083). The
 * deployed stacks never set {@code alert.ai.provider=anthropic}; they use Bedrock or no model at all.
 * The key comes only from the environment and is never logged.
 */
@ConfigurationProperties(prefix = "alert.ai.anthropic")
public record AnthropicAiProperties(String apiKey, String endpoint, String version) {

    public AnthropicAiProperties {
        apiKey = apiKey == null ? "" : apiKey.strip();
        endpoint = endpoint == null || endpoint.isBlank() ? "https://api.anthropic.com/v1/messages" : endpoint.strip();
        version = version == null || version.isBlank() ? "2023-06-01" : version.strip();
    }

    public boolean hasKey() {
        return !apiKey.isBlank();
    }

    /** Never prints the key. */
    @Override
    public String toString() {
        return "AnthropicAiProperties[endpoint=" + endpoint + ", version=" + version + ", apiKey=" + (hasKey() ? "set" : "missing") + "]";
    }
}
