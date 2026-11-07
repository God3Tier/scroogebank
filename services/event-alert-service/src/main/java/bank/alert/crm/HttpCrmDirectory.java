package bank.alert.crm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calls account-service's private alert-directory route with a
 * dedicated client-credentials token. The token is held only until shortly
 * before it expires and is never logged.
 */
public class HttpCrmDirectory implements CrmDirectory {

    private static final long EXPIRY_MARGIN_SECONDS = 30;

    private final CrmDirectoryProperties properties;
    private final Clock clock;
    private final RestClient http;
    private String token;
    private Instant tokenExpiry = Instant.EPOCH;

    private final Runnable onFailure;

    public HttpCrmDirectory(CrmDirectoryProperties properties, Clock clock) {
        this(properties, clock, () -> { });
    }

    /** @param onFailure called once for each lookup that fails, for aggregate metrics */
    public HttpCrmDirectory(CrmDirectoryProperties properties, Clock clock, Runnable onFailure) {
        this.onFailure = onFailure;
        properties.requireComplete();
        this.properties = properties;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) properties.timeout().toMillis());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Map<UUID, AccountContext> accounts(Collection<UUID> accountIds) {
        try {
            Map<UUID, AccountContext> result = new LinkedHashMap<>();
            for (List<UUID> batch : batches(accountIds)) {
                for (JsonNode node : lookup(properties.accountDirectoryUrl(), batch)) {
                    UUID id = uuid(node, "id");
                    result.put(id, new AccountContext(id, uuid(node, "client_id"),
                            text(node, "type"), text(node, "status"), text(node, "currency"),
                            text(node, "owner_agent_id")));
                }
            }
            return result;
        } catch (DirectoryUnavailableException exception) {
            onFailure.run();
            throw exception;
        }
    }

    @Override
    public boolean agentCanAccess(UUID accountId, String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) return false;
        String url = properties.userAccountUrl() + accountId;
        try {
            http.get().uri(url).headers(headers -> headers.setBearerAuth(bearerToken))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (org.springframework.web.client.HttpClientErrorException refused) {
            int status = refused.getStatusCode().value();
            if (status == 401 || status == 403 || status == 404) return false;
            onFailure.run();
            throw new DirectoryUnavailableException("CRM account check returned an unexpected response", refused);
        } catch (RestClientException exception) {
            onFailure.run();
            throw new DirectoryUnavailableException("CRM account check is unavailable", exception);
        }
    }

    private JsonNode lookup(String url, List<UUID> ids) {
        try {
            JsonNode body = http.post().uri(url)
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("ids", ids))
                    .retrieve().body(JsonNode.class);
            JsonNode items = body == null ? null : body.get("items");
            if (items == null || !items.isArray()) {
                throw new DirectoryUnavailableException("CRM directory returned an unexpected response");
            }
            return items;
        } catch (RestClientException exception) {
            throw new DirectoryUnavailableException("CRM directory is unavailable", exception);
        }
    }

    private synchronized String accessToken() {
        Instant now = clock.instant();
        if (token != null && now.isBefore(tokenExpiry.minusSeconds(EXPIRY_MARGIN_SECONDS))) {
            return token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", properties.scope());
        JsonNode body = http.post().uri(properties.tokenEndpoint())
                .headers(headers -> headers.setBasicAuth(properties.clientId(), properties.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(JsonNode.class);
        if (body == null || !body.hasNonNull("access_token")) {
            throw new DirectoryUnavailableException("CRM token endpoint returned no access token");
        }
        token = body.get("access_token").asText();
        tokenExpiry = now.plusSeconds(body.path("expires_in").asLong(300));
        return token;
    }

    private static List<List<UUID>> batches(Collection<UUID> ids) {
        List<UUID> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        List<List<UUID>> batches = new ArrayList<>();
        for (int from = 0; from < distinct.size(); from += MAX_LOOKUP_IDS) {
            batches.add(distinct.subList(from, Math.min(distinct.size(), from + MAX_LOOKUP_IDS)));
        }
        return batches;
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(node.path(field).asText());
        } catch (IllegalArgumentException exception) {
            throw new DirectoryUnavailableException("CRM directory returned an invalid identifier");
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
