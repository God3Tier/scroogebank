package bank.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.io.IOException;

@Service
@RequiredArgsConstructor
public class CorruptedAuditLogProxy {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${auditor.base-url}")
    private String auditorBaseUrl;

    // Each call forwards the admin's own access token, which the Auditor
    // checks itself (ADR-0021).

    public ResponseEntity<JsonNode> getCorruptedLogs(String accessToken) {
        return request(HttpMethod.GET, "", null, accessToken);
    }

    public ResponseEntity<JsonNode> createCorruptedLog(JsonNode request, String accessToken) {
        return request(HttpMethod.POST, "", request, accessToken);
    }

    public ResponseEntity<JsonNode> deleteCorruptedLog(UUID id, String accessToken) {
        return request(HttpMethod.DELETE, "/" + id, null, accessToken);
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, JsonNode body, String accessToken) {
        RestClient.RequestBodySpec request = restClientBuilder.build()
                .method(method)
                .uri(auditorBaseUrl + "/api/corrupted-logs" + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        if (body != null) {
            request.body(body);
        }
        return request.exchange((clientRequest, clientResponse) -> {
            JsonNode responseBody;
            try {
                responseBody = objectMapper.readTree(clientResponse.getBody());
            } catch (IOException e) {
                throw new IllegalStateException("Auditor returned an unreadable response", e);
            }
            return ResponseEntity.status(clientResponse.getStatusCode())
                    .body(responseBody);
        });
    }
}
