package bank.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.LocalDate;

/**
 * The business dashboard's transaction figures (ADR-0039).
 *
 * <p>The receiver has no authentication of its own (ADR-0006 decision 5), so
 * this is where the caller's scope is decided, the same way as for a single
 * account's transactions: an admin's totals cover every transaction, and an
 * agent's cover only the accounts account-service says they own.
 */
@Service
@RequiredArgsConstructor
public class TransactionStatsProxy {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${account-service.base-url}")
    private String accountServiceBaseUrl;

    @Value("${sftp-receiver.base-url}")
    private String receiverBaseUrl;

    public ResponseEntity<JsonNode> getStats(LocalDate from, LocalDate to, boolean admin, String authorization) {
        ObjectNode query = objectMapper.createObjectNode()
                .put("from", from.toString())
                .put("to", to.toString());
        if (admin) {
            query.put("all", true);
        } else {
            ResponseEntity<JsonNode> owned = request(HttpMethod.GET,
                    accountServiceBaseUrl + "/aggregates/accounts/ids", authorization, null);
            if (!owned.getStatusCode().is2xxSuccessful()) {
                return owned;
            }
            JsonNode accountIds = owned.getBody() == null ? null : owned.getBody().get("accountIds");
            if (accountIds == null || !accountIds.isArray()) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(objectMapper.createObjectNode().put("error", "account-service returned no account list"));
            }
            query.set("accountIds", accountIds);
        }
        // The caller's token stays here: the receiver is told the scope instead.
        return request(HttpMethod.POST, receiverBaseUrl + "/aggregates/transactions/stats", null, query);
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String uri, String authorization, JsonNode body) {
        RestClient.RequestBodySpec request = restClientBuilder.build()
                .method(method)
                .uri(uri);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((clientRequest, clientResponse) -> {
            JsonNode responseBody;
            try {
                responseBody = objectMapper.readTree(clientResponse.getBody());
            } catch (IOException e) {
                throw new IllegalStateException("Upstream service returned an unreadable response", e);
            }
            return ResponseEntity.status(clientResponse.getStatusCode()).body(responseBody);
        });
    }
}
