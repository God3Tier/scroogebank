package bank.engagement.integration.clients;

import bank.engagement.security.CurrentUser;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Reads one client from client-service's {@code GET /api/clients/{clientId}}
 * with the caller's own token, as account-service does (ADR-0035). 401, 403 and
 * 404 all mean "not yours"; anything else is client-service being unavailable.
 *
 * <p>The body is read as text and parsed here: client-service writes JSON
 * without a JSON content type, so Spring's converters would refuse it.
 */
@Component
public class HttpClientDirectory implements ClientDirectory {

    private final RestClient restClient;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public HttpClientDirectory(@Qualifier(ClientServiceConfiguration.REST_CLIENT) RestClient restClient,
            CurrentUser currentUser, ObjectMapper objectMapper, ClientServiceProperties properties) {
        this.restClient = restClient;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.baseUrl = properties.baseUrl();
    }

    @Override
    public ClientRecord findOwned(UUID clientId) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new ClientServiceUnavailableException("client-service base URL is not configured");
        }
        URI target = UriComponentsBuilder.fromUriString(StringUtils.trimTrailingCharacter(baseUrl, '/'))
                .path("/api/clients/{clientId}")
                .buildAndExpand(clientId)
                .toUri();
        try {
            String text = restClient.get()
                    .uri(target)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + currentUser.tokenValue())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403 || status == 404) {
                            throw new ClientNotFoundException();
                        }
                        throw new ClientServiceUnavailableException("client-service returned " + status);
                    })
                    .body(String.class);
            ClientResponse body = text == null ? null : objectMapper.readValue(text, ClientResponse.class);
            if (body == null || body.clientId() == null) {
                throw new ClientServiceUnavailableException("client-service returned no client");
            }
            return new ClientRecord(body.clientId(), body.firstName(), body.email(), body.country());
        } catch (ClientNotFoundException | ClientServiceUnavailableException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new ClientServiceUnavailableException("client-service could not be reached", ex);
        } catch (JsonProcessingException ex) {
            throw new ClientServiceUnavailableException("client-service returned an unreadable client", ex);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClientResponse(
            @JsonProperty("client_id") UUID clientId,
            @JsonProperty("first_name") String firstName,
            String email,
            String country) {
    }
}
