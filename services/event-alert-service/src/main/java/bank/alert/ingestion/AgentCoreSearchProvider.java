package bank.alert.ingestion;

import java.util.List;
import java.util.Objects;

/**
 * Provider adapter for an operator-supplied, IAM-authenticated Gateway client.
 * The AWS client is deliberately outside this module until EDA-D1-02 is approved.
 */
public class AgentCoreSearchProvider implements PublicSearchProvider {

    public static final String TOOL_VERSION = "1.2.0";

    private final AgentCoreGatewayClient client;
    private final List<String> administratorDomains;

    public AgentCoreSearchProvider(AgentCoreGatewayClient client, List<String> administratorDomains) {
        this.client = Objects.requireNonNull(client);
        this.administratorDomains = List.copyOf(administratorDomains);
    }

    @Override
    public List<PublicSearchResult> search(PublicSearchRequest request) {
        if (!administratorDomains.containsAll(request.approvedDomains())) {
            throw new SearchProviderException("DOMAIN_SCOPE_EXPANSION", false,
                    "request attempted to expand the administrator domain allowlist");
        }
        AgentCoreSearchPayload payload = new AgentCoreSearchPayload(
                TOOL_VERSION,
                request.query(),
                request.approvedDomains(),
                request.publishedFrom().toString(),
                request.publishedTo().toString(),
                request.maximumResults());
        AgentCoreSearchResponse response = client.invoke(payload);
        if (response == null || response.results().size() > request.maximumResults()) {
            throw new SearchProviderException("INVALID_PROVIDER_RESPONSE", false,
                    "provider response did not satisfy the bounded contract");
        }
        return response.results();
    }
}
