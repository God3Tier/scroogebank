package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "alert.ai")
public record AiProperties(boolean enabled, String model, String promptVersion,
        int maxInputCharacters, int maxOutputCharacters, int maxOutputTokens,
        int maxAttempts, Duration timeout, BigDecimal minimumConfidence) {

    public AiProperties {
        model = model == null || model.isBlank() ? "apac.amazon.nova-pro-v1:0" : model;
        promptVersion = promptVersion == null || promptVersion.isBlank() ? "alert-public-v1" : promptVersion;
        maxInputCharacters = maxInputCharacters <= 0 ? 12_000 : Math.min(maxInputCharacters, 20_000);
        maxOutputCharacters = maxOutputCharacters <= 0 ? 8_000 : Math.min(maxOutputCharacters, 12_000);
        maxOutputTokens = maxOutputTokens <= 0 ? 1_200 : Math.min(maxOutputTokens, 2_000);
        maxAttempts = maxAttempts <= 0 ? 2 : Math.min(maxAttempts, 2);
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        minimumConfidence = minimumConfidence == null ? new BigDecimal("0.60") : minimumConfidence;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("alert.ai.timeout must be positive and at most 30 seconds");
        }
        if (minimumConfidence.compareTo(BigDecimal.ZERO) < 0
                || minimumConfidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("alert.ai.minimum-confidence must be between zero and one");
        }
    }
}
