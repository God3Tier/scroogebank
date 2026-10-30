package bank.clienthealth.transaction.adapter.http;

import bank.clienthealth.transaction.contract.MatchedTransaction;
import bank.clienthealth.transaction.contract.MatchedTransaction.ActivityKind;
import bank.clienthealth.transaction.contract.MatchedTransaction.Status;
import bank.clienthealth.transaction.contract.MatchedTransaction.TransactionType;
import bank.clienthealth.transaction.contract.TransactionReplayPage;
import bank.clienthealth.transaction.port.TransactionReplaySource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/** Strict HTTP consumer for the matched transaction-history contract. */
public final class HttpTransactionReplaySource implements TransactionReplaySource {
    private static final Pattern AMOUNT = Pattern.compile("(0|[1-9][0-9]*)(\\.[0-9]{1,2})?");
    private static final Pattern SOURCE_FINGERPRINT =
            Pattern.compile("synthetic-[a-z0-9]+(-[a-z0-9]+)*-[a-f0-9]{12}");
    private final RestClient client;
    private final TransactionReplayTokenProvider tokens;
    private final TransactionReplayProperties properties;
    private final ObjectMapper json;

    HttpTransactionReplaySource(
            RestClient client,
            TransactionReplayTokenProvider tokens,
            TransactionReplayProperties properties,
            ObjectMapper json
    ) {
        this.client = client;
        this.tokens = tokens;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public TransactionReplayPage fetch(Request request) {
        var uri = UriComponentsBuilder.fromUri(properties.endpoint())
                .queryParam("from", request.from())
                .queryParam("to", request.to())
                .queryParam("limit", request.limit());
        if (request.cursor() != null) uri.queryParam("cursor", request.cursor());
        try {
            String body = client.get().uri(uri.build().encode().toUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (sent, response) -> {
                        throw new TransactionReplayProviderException("Transaction provider refused the request");
                    })
                    .body(String.class);
            ProviderPage page = json.readValue(body, ProviderPage.class);
            List<MatchedTransaction> items = page.items().stream().map(ProviderItem::toDomain).toList();
            return new TransactionReplayPage(page.schemaVersion(), items, page.nextCursor());
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException failure) {
            throw new TransactionReplayProviderException("Transaction provider response was invalid", failure);
        } catch (RestClientException failure) {
            throw new TransactionReplayProviderException("Transaction provider request failed", failure);
        }
    }

    private record ProviderPage(String schemaVersion, List<ProviderItem> items, String nextCursor) { }

    private record ProviderItem(
            String transactionId, String clientId, String accountId, String type,
            String amount, String currency, Instant transactionDate, String status,
            String activityKind, String sourceFingerprint
    ) {
        MatchedTransaction toDomain() {
            if (amount == null || !AMOUNT.matcher(amount).matches()) {
                throw new IllegalArgumentException("Invalid amount");
            }
            if (sourceFingerprint != null
                    && (sourceFingerprint.length() < 24
                    || sourceFingerprint.length() > 64
                    || !SOURCE_FINGERPRINT.matcher(sourceFingerprint).matches())) {
                throw new IllegalArgumentException("Invalid source fingerprint");
            }
            return new MatchedTransaction(
                    transactionId, clientId, accountId,
                    switch (type) {
                        case "Deposit" -> TransactionType.DEPOSIT;
                        case "Withdrawal" -> TransactionType.WITHDRAWAL;
                        default -> throw new IllegalArgumentException("Invalid transaction type");
                    },
                    new BigDecimal(amount), currency, transactionDate,
                    switch (status) {
                        case "Completed" -> Status.COMPLETED;
                        case "Pending" -> Status.PENDING;
                        case "Failed" -> Status.FAILED;
                        default -> throw new IllegalArgumentException("Invalid transaction status");
                    },
                    switch (activityKind) {
                        case "CustomerInitiated" -> ActivityKind.CUSTOMER_INITIATED;
                        case "Automated" -> ActivityKind.AUTOMATED;
                        case "Unknown" -> ActivityKind.UNKNOWN;
                        default -> throw new IllegalArgumentException("Invalid activity kind");
                    },
                    sourceFingerprint);
        }
    }
}
