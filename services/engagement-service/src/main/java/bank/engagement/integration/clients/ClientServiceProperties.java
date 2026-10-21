package bank.engagement.integration.clients;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param baseUrl client-service; blank fails every lookup closed
 * @param timeout connect and read timeout for one call
 */
@ConfigurationProperties("engagement.clients")
public record ClientServiceProperties(String baseUrl, Duration timeout) {

    public ClientServiceProperties {
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }
}
