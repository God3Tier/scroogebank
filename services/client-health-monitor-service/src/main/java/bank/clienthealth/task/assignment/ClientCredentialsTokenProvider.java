package bank.clienthealth.task.assignment;

import bank.clienthealth.task.port.ClientAssignmentPort.AssignmentUnavailableException;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Obtains and briefly caches the scoped Cognito client-credentials token. */
public final class ClientCredentialsTokenProvider {
    private final RestClient restClient;
    private final Clock clock;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private volatile CachedToken cached;

    public ClientCredentialsTokenProvider(
            RestClient restClient, Clock clock, String tokenUri,
            String clientId, String clientSecret, String scope
    ) {
        this.restClient = restClient;
        this.clock = clock;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
    }

    public synchronized String token() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.expiresAt().minusSeconds(30))) return cached.value();
        requireConfiguration();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        if (StringUtils.hasText(scope)) form.add("scope", scope);
        try {
            TokenResponse response = restClient.post().uri(tokenUri)
                    .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(TokenResponse.class);
            if (response == null || !StringUtils.hasText(response.accessToken()) || response.expiresIn() <= 30) {
                throw unavailable("Cognito returned an invalid client-credentials response", null);
            }
            cached = new CachedToken(response.accessToken(), now.plusSeconds(response.expiresIn()));
            return cached.value();
        } catch (RestClientException exception) {
            throw unavailable("Cognito client-credentials request failed", exception);
        }
    }

    private void requireConfiguration() {
        if (!StringUtils.hasText(tokenUri) || !StringUtils.hasText(clientId)
                || !StringUtils.hasText(clientSecret)) {
            throw unavailable("Client-assignment OAuth configuration is incomplete", null);
        }
    }

    private static AssignmentUnavailableException unavailable(String message, Exception cause) {
        AssignmentUnavailableException exception = new AssignmentUnavailableException(message);
        if (cause != null) exception.initCause(cause);
        return exception;
    }

    private record CachedToken(String value, Instant expiresAt) { }
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn
    ) { }
}
