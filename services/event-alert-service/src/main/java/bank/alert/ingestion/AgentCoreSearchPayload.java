package bank.alert.ingestion;

import java.util.List;

public record AgentCoreSearchPayload(
        String toolVersion,
        String query,
        List<String> domains,
        String publishedFromUtc,
        String publishedToUtc,
        int maximumResults) {
}
