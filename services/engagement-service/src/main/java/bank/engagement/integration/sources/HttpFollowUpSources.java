package bank.engagement.integration.sources;

import bank.engagement.security.CurrentUser;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * Reads a Client Health task ({@code GET /api/relationship-health/tasks/{taskId}})
 * or an alert ({@code GET /api/alerts/{alertId}}) through the internal ALB with
 * the caller's own token, as {@code HttpClientDirectory} reads clients. 401,
 * 403 and 404 all mean "not yours"; anything else is the service being
 * unavailable. Only the client field is parsed (ADR-0068).
 */
@Component
public class HttpFollowUpSources implements FollowUpSources {

    private final RestClient restClient;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    private final FollowUpSourceProperties properties;

    public HttpFollowUpSources(@Qualifier(FollowUpSourceConfiguration.REST_CLIENT) RestClient restClient,
            CurrentUser currentUser, ObjectMapper objectMapper, FollowUpSourceProperties properties) {
        this.restClient = restClient;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String healthTaskClient(String taskId) {
        HealthTask task = read(properties.healthBaseUrl(), "/api/relationship-health/tasks/{taskId}", taskId,
                HealthTask.class, "Client Health");
        if (task.clientId() == null || task.clientId().isBlank()) {
            throw new SourceUnavailableException("Client Health returned no client");
        }
        return task.clientId();
    }

    private <T> T read(String baseUrl, String path, Object id, Class<T> type, String service) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new SourceUnavailableException(service + " base URL is not configured");
        }
        URI target = UriComponentsBuilder.fromUriString(StringUtils.trimTrailingCharacter(baseUrl, '/'))
                .path(path)
                .encode()
                .buildAndExpand(id)
                .toUri();
        try {
            String text = restClient.get()
                    .uri(target)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + currentUser.tokenValue())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403 || status == 404) {
                            throw new SourceNotFoundException();
                        }
                        throw new SourceUnavailableException(service + " returned " + status);
                    })
                    .body(String.class);
            T body = text == null ? null : objectMapper.readValue(text, type);
            if (body == null) {
                throw new SourceUnavailableException(service + " returned nothing");
            }
            return body;
        } catch (SourceNotFoundException | SourceUnavailableException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new SourceUnavailableException(service + " could not be reached", ex);
        } catch (JsonProcessingException ex) {
            throw new SourceUnavailableException(service + " returned an unreadable body", ex);
        }
    }

    /** The one field read from a task; its explanation and evidence are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HealthTask(String clientId) {
    }

    /** The one field read from an alert; its headline, holdings and briefing are ignored. */
}
