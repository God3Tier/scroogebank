package bank.dashboard.upstream.http;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.upstream.ClientStatsPort;
import bank.dashboard.upstream.dto.ClientActionItems;
import bank.dashboard.upstream.dto.ClientStats;
import bank.dashboard.upstream.dto.TeamComparison;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Client figures from client-service's {@code /aggregates/clients} (ADR-0039). */
@Component
@RequiredArgsConstructor
public class HttpClientStatsAdapter implements ClientStatsPort {

    private static final String SERVICE = "client-profile-management-service";

    private final UpstreamClient upstream;
    private final DashboardProperties properties;

    @Override
    public ClientStats stats(DashboardWindow window, boolean groupByAgent) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("from", window.from());
        query.put("to", window.to());
        if (groupByAgent) {
            query.put("groupBy", "agent");
        }
        return upstream.get(SERVICE, properties.upstream().clientServiceBaseUrl(),
                "/aggregates/clients/stats", query, ClientStats.class);
    }

    @Override
    public ClientActionItems actionItems(int staleDays) {
        return upstream.get(SERVICE, properties.upstream().clientServiceBaseUrl(),
                "/aggregates/clients/action-items", Map.of("staleDays", staleDays), ClientActionItems.class);
    }

    @Override
    public TeamComparison teamComparison(int days) {
        return upstream.get(SERVICE, properties.upstream().clientServiceBaseUrl(),
                "/aggregates/clients/team-comparison", Map.of("days", days), TeamComparison.class);
    }
}
