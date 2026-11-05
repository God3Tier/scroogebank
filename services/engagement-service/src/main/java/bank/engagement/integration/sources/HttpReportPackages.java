package bank.engagement.integration.sources;

import bank.engagement.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code POST /api/alerts/events/{eventId}/report/delivery-package} through the internal ALB with the caller's own
 * token. 401, 403 and 404 mean the agent has no such alert; 409 means the report moved on; anything else is the
 * service being unavailable.
 */
@Component
public class HttpReportPackages implements ReportPackages {

    private final RestClient restClient;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    private final FollowUpSourceProperties properties;

    public HttpReportPackages(@Qualifier(FollowUpSourceConfiguration.REST_CLIENT) RestClient restClient,
            CurrentUser currentUser, ObjectMapper objectMapper, FollowUpSourceProperties properties) {
        this.restClient = restClient;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Package fetch(UUID eventId, int expectedVersion, List<UUID> alertIds) {
        if (!StringUtils.hasText(properties.alertsBaseUrl())) {
            throw new SourceUnavailableException("event-alert-service base URL is not configured");
        }
        URI target = UriComponentsBuilder.fromUriString(StringUtils.trimTrailingCharacter(properties.alertsBaseUrl(), '/'))
                .path("/api/alerts/events/{eventId}/report/delivery-package").encode().buildAndExpand(eventId).toUri();
        try {
            String text = restClient.post().uri(target)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + currentUser.tokenValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("expectedReportVersion", expectedVersion, "alertIds", alertIds))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        int status = res.getStatusCode().value();
                        if (status == 401 || status == 403 || status == 404) {
                            throw new SourceNotFoundException();
                        }
                        if (status == 409) {
                            throw new StaleReportVersionException();
                        }
                        throw new SourceUnavailableException("event-alert-service returned " + status);
                    })
                    .body(String.class);
            Package body = text == null ? null : objectMapper.readValue(text, Package.class);
            if (body == null || body.report() == null || body.reportId() == null) {
                throw new SourceUnavailableException("event-alert-service returned an incomplete report");
            }
            return body;
        } catch (SourceNotFoundException | SourceUnavailableException | StaleReportVersionException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new SourceUnavailableException("event-alert-service could not be reached", ex);
        } catch (JsonProcessingException ex) {
            throw new SourceUnavailableException("event-alert-service returned an unreadable body", ex);
        }
    }
}
