package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Where the Tokyo AgentCore Gateway is (ADR-0031). With no gateway URL there is no
 * client and every search fails cleanly as PROVIDER_NOT_CONFIGURED.
 */
@ConfigurationProperties(prefix = "alert.ingestion.agentcore")
public record AgentCoreProperties(String gatewayUrl, String region, Duration timeout) {

    public AgentCoreProperties {
        gatewayUrl = gatewayUrl == null || gatewayUrl.isBlank() ? null : gatewayUrl.strip();
        region = region == null || region.isBlank() ? "ap-northeast-1" : region.strip();
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("alert.ingestion.agentcore.timeout must be between 1ms and 2 minutes");
        }
        if (gatewayUrl != null) {
            URI uri = URI.create(gatewayUrl);
            boolean loopback = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            if (uri.getHost() == null || !("https".equals(uri.getScheme()) || loopback && "http".equals(uri.getScheme()))) {
                throw new IllegalArgumentException(
                        "alert.ingestion.agentcore.gateway-url must be an https URL (http only on localhost for tests)");
            }
        }
    }
}
