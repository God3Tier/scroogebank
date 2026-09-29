package bank.alert.ingestion;

public interface AgentCoreGatewayClient {
    AgentCoreSearchResponse invoke(AgentCoreSearchPayload payload);
}
