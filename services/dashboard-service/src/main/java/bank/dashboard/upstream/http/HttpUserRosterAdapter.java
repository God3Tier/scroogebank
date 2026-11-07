package bank.dashboard.upstream.http;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.PanelReason;
import bank.dashboard.exception.UpstreamUnavailableException;
import bank.dashboard.upstream.UserRosterPort;
import bank.dashboard.upstream.dto.AgentSummary;
import bank.dashboard.upstream.dto.UserPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent roster from user-service's admin-only, paged {@code GET /api/users}.
 *
 * <p>Every agent, enabled or not: a disabled agent's clients and accounts
 * still count, and the per-agent table still needs their name. The roster is
 * read page by page; each page uses user-service's largest page size.
 */
@Component
@RequiredArgsConstructor
public class HttpUserRosterAdapter implements UserRosterPort {

    /** user-service's {@code MAX_PAGE_SIZE}. */
    static final int PAGE_SIZE = 100;
    /** 10,000 agents. More means the roster would be partial, which is refused. */
    static final int MAX_PAGES = 100;

    private final UpstreamClient upstream;
    private final DashboardProperties properties;

    @Override
    public List<AgentSummary> agents() {
        List<AgentSummary> agents = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("role", "AGENT");
            query.put("page", page);
            query.put("size", PAGE_SIZE);
            UserPage result = upstream.get("user-service", properties.upstream().userServiceBaseUrl(),
                    "/api/users", query, UserPage.class);
            if (result.content() != null) {
                agents.addAll(result.content());
            }
            if (page + 1 >= result.totalPages()) {
                return agents;
            }
        }
        throw new UpstreamUnavailableException(PanelReason.UPSTREAM_ERROR,
                "user-service roster has more than " + MAX_PAGES * PAGE_SIZE + " agents");
    }
}
