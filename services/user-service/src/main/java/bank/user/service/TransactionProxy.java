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

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionProxy {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${client-profile.base-url}")
    private String clientProfileBaseUrl;

    @Value("${sftp-receiver.base-url}")
    private String receiverBaseUrl;

    public ResponseEntity<JsonNode> getTransactions(UUID accountId, String authorization) {
        ResponseEntity<JsonNode> account = request(
                clientProfileBaseUrl,
                "/api/accounts/" + accountId,
                authorization
        );
        if (!account.getStatusCode().is2xxSuccessful()) {
            return account;
        }
        return request(receiverBaseUrl, "/api/accounts/" + accountId + "/transactions", null);
    }

    private ResponseEntity<JsonNode> request(String baseUrl, String path, String authorization) {
        RestClient.RequestHeadersSpec<?> request = restClientBuilder.build()
                .method(HttpMethod.GET)
                .uri(baseUrl + path);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchange((clientRequest, clientResponse) -> {
            JsonNode body;
            try {
                body = objectMapper.readTree(clientResponse.getBody());
            } catch (IOException e) {
                throw new IllegalStateException("Upstream service returned an unreadable response", e);
            }
            return ResponseEntity.status(clientResponse.getStatusCode()).body(body);
        });
    }
}
