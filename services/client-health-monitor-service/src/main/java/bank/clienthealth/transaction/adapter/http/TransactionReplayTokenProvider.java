package bank.clienthealth.transaction.adapter.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Obtains and caches the transaction-read service token without logging credentials. */
final class TransactionReplayTokenProvider {
    private final RestClient client;
    private final TransactionReplayProperties properties;
    private final Clock clock;
    private volatile CachedToken cached;

    TransactionReplayTokenProvider(
            RestClient client, TransactionReplayProperties properties, Clock clock
    ) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    synchronized String token() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.expiresAt().minusSeconds(30))) {
            return cached.value();
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("scope", properties.scope());
        try {
            TokenResponse response = client.post().uri(properties.tokenUri())
                    .headers(headers -> headers.setBasicAuth(
                            properties.clientId(), properties.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(TokenResponse.class);
            if (response == null || !StringUtils.hasText(response.accessToken())
                    || response.expiresIn() <= 30) {
                throw new TransactionReplayProviderException("Token response was invalid");
            }
            cached = new CachedToken(response.accessToken(), now.plusSeconds(response.expiresIn()));
            return cached.value();
        } catch (RestClientException failure) {
            throw new TransactionReplayProviderException("Token request failed", failure);
        }
    }

    private record CachedToken(String value, Instant expiresAt) { }
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn
    ) { }
}
