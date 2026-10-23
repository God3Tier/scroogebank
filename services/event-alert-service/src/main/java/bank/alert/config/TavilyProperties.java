package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Tavily's public-news endpoint. The API key is injected from Secrets Manager
 * by the worker task and is deliberately redacted from {@link #toString()}.
 */
@ConfigurationProperties(prefix = "alert.ingestion.tavily")
public record TavilyProperties(String apiKey, URI endpoint, Duration timeout, int maxResponseBytes) {

    private static final URI OFFICIAL_ENDPOINT = URI.create("https://api.tavily.com/search");

    public TavilyProperties {
        apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
        endpoint = endpoint == null ? OFFICIAL_ENDPOINT : endpoint;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        maxResponseBytes = maxResponseBytes <= 0 ? 262_144 : maxResponseBytes;

        if (!OFFICIAL_ENDPOINT.equals(endpoint)) {
            throw new IllegalArgumentException("alert.ingestion.tavily.endpoint must be the official HTTPS search endpoint");
        }
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("alert.ingestion.tavily.timeout must be between 1ms and 2 minutes");
        }
        if (maxResponseBytes < 1_024 || maxResponseBytes > 1_048_576) {
            throw new IllegalArgumentException(
                    "alert.ingestion.tavily.max-response-bytes must be between 1024 and 1048576");
        }
    }

    @Override
    public String toString() {
        return "TavilyProperties[apiKey=<redacted>, endpoint=" + endpoint + ", timeout=" + timeout
                + ", maxResponseBytes=" + maxResponseBytes + "]";
    }
}
