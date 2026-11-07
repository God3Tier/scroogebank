package bank.engagement.integration.sources;

import bank.engagement.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * event-alert-service's news-alert routes through the internal ALB with the caller's own token. 401, 403 and 404
 * mean the agent has no such event or approval; anything else is the service being unavailable. The combined PDF
 * has its own, longer read timeout.
 */
@Component
public class HttpNewsReports implements NewsReports {

    private final RestClient restClient;
    private final RestClient pdfClient;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    private final FollowUpSourceProperties properties;

    public HttpNewsReports(@Qualifier(FollowUpSourceConfiguration.REST_CLIENT) RestClient restClient,
            @Qualifier(FollowUpSourceConfiguration.PDF_REST_CLIENT) RestClient pdfClient, CurrentUser currentUser,
            ObjectMapper objectMapper, FollowUpSourceProperties properties) {
        this.restClient = restClient;
        this.pdfClient = pdfClient;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<PackageItem> packageFor(UUID eventId, List<UUID> alertIds) {
        return post(restClient, "/api/alerts/approved-reports/package", Map.of("eventId", eventId, "alertIds", alertIds),
                new TypeReference<List<PackageItem>>() {});
    }

    @Override
    public List<CheckItem> sendCheck(List<UUID> alertIds) {
        return post(restClient, "/api/alerts/send-check", Map.of("alertIds", alertIds),
                new TypeReference<List<CheckItem>>() {});
    }

    @Override
    public Pdf combinedPdf(List<UUID> approvalIds, String preparedBy) {
        Map<String, Object> body = new HashMap<>();
        body.put("approvalIds", approvalIds);
        body.put("preparedBy", preparedBy);
        Pdf pdf = post(pdfClient, "/api/alerts/approved-reports/pdf", body, new TypeReference<Pdf>() {});
        if (pdf.pdfBase64() == null || pdf.sections().isEmpty()) {
            throw new SourceUnavailableException("event-alert-service returned an incomplete PDF");
        }
        return pdf;
    }

    @Override
    public List<SentOutItem> sentOut(List<UUID> alertIds, UUID draftId, String sesMessageId) {
        return post(restClient, "/api/alerts/sent-out",
                Map.of("alertIds", alertIds, "draftId", draftId, "sesMessageId", sesMessageId),
                new TypeReference<List<SentOutItem>>() {});
    }

    private <T> T post(RestClient client, String path, Object body, TypeReference<T> type) {
        if (!StringUtils.hasText(properties.alertsBaseUrl())) {
            throw new SourceUnavailableException("event-alert-service base URL is not configured");
        }
        URI target = URI.create(StringUtils.trimTrailingCharacter(properties.alertsBaseUrl(), '/') + path);
        try {
            String text = client.post().uri(target)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + currentUser.tokenValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        int status = res.getStatusCode().value();
                        if (status == 401 || status == 403 || status == 404) {
                            throw new SourceNotFoundException();
                        }
                        throw new SourceUnavailableException("event-alert-service returned " + status);
                    })
                    .body(String.class);
            T result = text == null ? null : objectMapper.readValue(text, type);
            if (result == null) {
                throw new SourceUnavailableException("event-alert-service returned no body");
            }
            return result;
        } catch (SourceNotFoundException | SourceUnavailableException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new SourceUnavailableException("event-alert-service could not be reached", ex);
        } catch (JsonProcessingException ex) {
            throw new SourceUnavailableException("event-alert-service returned an unreadable body", ex);
        }
    }
}
