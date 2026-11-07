package bank.dashboard.upstream.http;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.upstream.TransactionStatsPort;
import bank.dashboard.upstream.dto.TransactionStats;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transaction figures from transaction-service's
 * {@code /aggregates/transactions/stats}, which scopes them by the caller's
 * token (ADR-0064).
 */
@Component
@RequiredArgsConstructor
public class HttpTransactionStatsAdapter implements TransactionStatsPort {

    private final UpstreamClient upstream;
    private final DashboardProperties properties;

    @Override
    public TransactionStats stats(DashboardWindow window) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("from", window.from());
        query.put("to", window.to());
        return upstream.get("transaction-service", properties.upstream().transactionServiceBaseUrl(),
                "/aggregates/transactions/stats", query, TransactionStats.class);
    }
}
