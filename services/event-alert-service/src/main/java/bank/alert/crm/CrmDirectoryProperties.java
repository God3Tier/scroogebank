package bank.alert.crm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code alert.crm.*}. Required only when {@code alert.holdings.mode=database}; the
 * client-credentials identity here is a dedicated machine client that holds only the
 * directory read scopes, never a user token.
 */
@ConfigurationProperties(prefix = "alert.crm")
public record CrmDirectoryProperties(
        String accountDirectoryUrl,
        String clientDirectoryUrl,
        String tokenEndpoint,
        String clientId,
        String clientSecret,
        String scope,
        Duration timeout) {

    public CrmDirectoryProperties {
        if (timeout == null) timeout = Duration.ofSeconds(5);
    }

    public void requireComplete() {
        require(accountDirectoryUrl, "ALERT_CRM_ACCOUNT_DIRECTORY_URL");
        require(clientDirectoryUrl, "ALERT_CRM_CLIENT_DIRECTORY_URL");
        require(tokenEndpoint, "ALERT_CRM_TOKEN_ENDPOINT");
        require(clientId, "ALERT_CRM_CLIENT_ID");
        require(clientSecret, "ALERT_CRM_CLIENT_SECRET");
        require(scope, "ALERT_CRM_SCOPE");
        for (String url : new String[] {accountDirectoryUrl, clientDirectoryUrl, tokenEndpoint}) {
            if (!url.startsWith("https://") && !isLoopbackHttp(url)) {
                throw new IllegalStateException("CRM directory URLs must use HTTPS");
            }
        }
    }

    private static boolean isLoopbackHttp(String url) {
        return url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when alert.holdings.mode=database");
        }
    }
}
