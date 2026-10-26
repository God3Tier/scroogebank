package bank.clienthealth.explanation;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("client-health.ai")
public record ClientHealthAiProperties(
        boolean enabled, String modelId, Duration timeout, int maxAttempts,
        int maxInputCharacters, int maxOutputTokens,
        BigDecimal inputCostPerMillionTokens, BigDecimal outputCostPerMillionTokens
) {
    public ClientHealthAiProperties {
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        maxAttempts = maxAttempts == 0 ? 2 : maxAttempts;
        maxInputCharacters = maxInputCharacters == 0 ? 6000 : maxInputCharacters;
        maxOutputTokens = maxOutputTokens == 0 ? 256 : maxOutputTokens;
        if (enabled && (modelId == null || modelId.isBlank())) {
            throw new IllegalArgumentException("client-health.ai.model-id is required when AI is enabled");
        }
        if (timeout.isNegative() || timeout.isZero() || maxAttempts < 1 || maxAttempts > 2
                || maxInputCharacters < 1 || maxOutputTokens < 1) {
            throw new IllegalArgumentException("Client Health AI bounds are invalid");
        }
    }
}
