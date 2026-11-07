package bank.engagement.integration.clients;

import java.util.UUID;

/**
 * client-service, asked with the calling agent's token. client-service decides
 * ownership: a client the agent does not own, a deleted client and an unknown
 * one all come back as not found.
 */
public interface ClientDirectory {

    /**
     * @throws ClientNotFoundException           the agent may not see this client
     * @throws ClientServiceUnavailableException client-service could not answer;
     *                                           callers fail closed
     */
    ClientRecord findOwned(UUID clientId);
}
