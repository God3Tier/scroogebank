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
 * Transaction figures from user-service's {@code /aggregates/transactions/stats}.
 * The receiver that holds the transactions has no authentication of its own,
 * so user-service decides the caller's scope in front of it (ADR-0039).
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
        return upstream.get("user-service", properties.upstream().userServiceBaseUrl(),
                "/aggregates/transactions/stats", query, TransactionStats.class);
    }
}
