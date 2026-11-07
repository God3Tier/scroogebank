package bank.dashboard.upstream.http;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.upstream.AccountStatsPort;
import bank.dashboard.upstream.dto.AccountStats;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Account figures from account-service's {@code /aggregates/accounts/stats} (ADR-0039). */
@Component
@RequiredArgsConstructor
public class HttpAccountStatsAdapter implements AccountStatsPort {

    private final UpstreamClient upstream;
    private final DashboardProperties properties;

    @Override
    public AccountStats stats(DashboardWindow window, boolean groupByAgent) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("from", window.from());
        query.put("to", window.to());
        if (groupByAgent) {
            query.put("groupBy", "agent");
        }
        return upstream.get("account-service", properties.upstream().accountServiceBaseUrl(),
                "/aggregates/accounts/stats", query, AccountStats.class);
    }
}
