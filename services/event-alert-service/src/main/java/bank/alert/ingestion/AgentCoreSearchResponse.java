package bank.alert.ingestion;

import java.util.List;

public record AgentCoreSearchResponse(List<PublicSearchResult> results) {
    public AgentCoreSearchResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
