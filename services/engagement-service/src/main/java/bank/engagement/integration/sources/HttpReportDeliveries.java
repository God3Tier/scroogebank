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
import java.util.Map;
import java.util.UUID;

/**
 * Calls event-alert-service ({@code /api/alerts/report-deliveries/...}) through the internal ALB with the
 * caller own token. 401, 403 and 404 mean the report was not approved for this agent and client; 409 means it
 * was already sent; anything else is the service being unavailable.
 */
@Component
public class HttpReportDeliveries implements ReportDeliveries {

    private static final String BASE = "/api/alerts/report-deliveries/{reportId}/{alertId}";

    private final RestClient restClient;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    private final FollowUpSourceProperties properties;

    public HttpReportDeliveries(@Qualifier(FollowUpSourceConfiguration.REST_CLIENT) RestClient restClient,
            CurrentUser currentUser, ObjectMapper objectMapper, FollowUpSourceProperties properties) {
        this.restClient = restClient;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Delivery get(UUID reportId, UUID alertId) {
        String text = call(true, "", reportId, alertId, null);
        try {
            Delivery delivery = text == null ? null : objectMapper.readValue(text, Delivery.class);
            if (delivery == null || delivery.clientRef() == null || delivery.report() == null) {
                throw new SourceUnavailableException("event-alert-service returned an incomplete report");
            }
            return delivery;
        } catch (JsonProcessingException ex) {
            throw new SourceUnavailableException("event-alert-service returned an unreadable body", ex);
        }
    }

    @Override
    public void claim(UUID reportId, UUID alertId) {
        call(false, "/claim", reportId, alertId, null);
    }

    @Override
    public void sent(UUID reportId, UUID alertId, String messageId) {
        call(false, "/sent", reportId, alertId, Map.of("messageId", messageId));
    }

    @Override
    public void release(UUID reportId, UUID alertId) {
        call(false, "/release", reportId, alertId, null);
    }

    private String call(boolean read, String suffix, UUID reportId, UUID alertId, Object body) {
        if (!StringUtils.hasText(properties.alertsBaseUrl())) {
            throw new SourceUnavailableException("event-alert-service base URL is not configured");
        }
        URI target = UriComponentsBuilder.fromUriString(StringUtils.trimTrailingCharacter(properties.alertsBaseUrl(), '/'))
                .path(BASE + suffix).encode().buildAndExpand(reportId, alertId).toUri();
        try {
            RestClient.ResponseSpec response;
            if (read) {
                response = restClient.get().uri(target)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + currentUser.tokenValue()).retrieve();
            } else {
                RestClient.RequestBodySpec request = restClient.post().uri(target)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + currentUser.tokenValue());
                if (body != null) {
                    request.contentType(MediaType.APPLICATION_JSON).body(body);
                }
                response = request.retrieve();
            }
            return response
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        int status = res.getStatusCode().value();
                        if (status == 401 || status == 403 || status == 404) {
                            throw new SourceNotFoundException();
                        }
                        if (status == 409) {
                            throw new ReportAlreadySentException();
                        }
                        throw new SourceUnavailableException("event-alert-service returned " + status);
                    })
                    .body(String.class);
        } catch (SourceNotFoundException | SourceUnavailableException | ReportAlreadySentException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new SourceUnavailableException("event-alert-service could not be reached", ex);
        }
    }
}
