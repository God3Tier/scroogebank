package bank.dashboard.upstream.dto;

import java.util.List;

/** One page of user-service's {@code GET /api/users}. */
public record UserPage(List<AgentSummary> content, int page, int size, long totalElements, int totalPages) {
}
