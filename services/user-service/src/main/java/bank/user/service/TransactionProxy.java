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
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionProxy {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${account-service.base-url}")
    private String accountServiceBaseUrl;

    @Value("${sftp-receiver.base-url}")
    private String receiverBaseUrl;

    public ResponseEntity<JsonNode> getTransactions(UUID accountId, String authorization) {
        ResponseEntity<JsonNode> account = request(
                accountServiceBaseUrl,
                "/api/accounts/" + accountId,
                authorization
        );
        if (!account.getStatusCode().is2xxSuccessful()) {
            return account;
        }
        return request(receiverBaseUrl, "/api/accounts/" + accountId + "/transactions", null);
    }

    /**
     * Every account's linked transactions, newest first (ADR-0043). The
     * controller lets only admins call this, so no ownership check is made;
     * the receiver validates the parameters.
     */
    public ResponseEntity<JsonNode> listTransactions(String cursor, Integer size, String status) {
        URI uri = UriComponentsBuilder.fromUriString(receiverBaseUrl)
                .path("/api/transactions")
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor))
                .queryParamIfPresent("size", Optional.ofNullable(size))
                .queryParamIfPresent("status", Optional.ofNullable(status))
                .encode()
                .build()
                .toUri();
        return request(uri, null);
    }

    private ResponseEntity<JsonNode> request(String baseUrl, String path, String authorization) {
        return request(URI.create(baseUrl + path), authorization);
    }

    private ResponseEntity<JsonNode> request(URI uri, String authorization) {
        RestClient.RequestHeadersSpec<?> request = restClientBuilder.build()
                .method(HttpMethod.GET)
                .uri(uri);
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
