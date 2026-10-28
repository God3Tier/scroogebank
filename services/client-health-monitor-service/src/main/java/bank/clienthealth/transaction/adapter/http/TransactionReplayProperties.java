package bank.clienthealth.transaction.adapter.http;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("client-health.transaction-replay")
public record TransactionReplayProperties(
        URI endpoint,
        URI tokenUri,
        String clientId,
        String clientSecret,
        String scope,
        Duration connectTimeout,
        Duration readTimeout,
        int pageSize,
        int maximumPages,
        int maximumRows
) {
    static final String REQUIRED_SCOPE = "crm-transactions/read";

    public TransactionReplayProperties {
        requireHttpsUri(endpoint, "endpoint");
        requireHttpsUri(tokenUri, "token-uri");
        if (!"/internal/v1/transactions".equals(endpoint.getPath())) {
            throw new IllegalArgumentException("Transaction replay endpoint path is invalid");
        }
        if (isBlank(clientId) || isBlank(clientSecret)) {
            throw new IllegalArgumentException("Transaction replay client credentials are required");
        }
        if (!REQUIRED_SCOPE.equals(scope)) {
            throw new IllegalArgumentException("Transaction replay scope must be " + REQUIRED_SCOPE);
        }
        if (!positive(connectTimeout) || !positive(readTimeout)) {
            throw new IllegalArgumentException("Transaction replay timeouts must be positive");
        }
        if (pageSize < 1 || pageSize > 500 || maximumPages < 1
                || maximumRows < pageSize) {
            throw new IllegalArgumentException("Transaction replay limits are invalid");
        }
    }

    private static void requireHttpsUri(URI uri, String name) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Transaction replay " + name + " must be an HTTPS URI");
        }
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
