package bank.alert.crm;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only view of the CRM's accounts (ADR-0066). Unknown or deleted IDs are simply
 * absent from the result; an unreachable CRM throws {@link DirectoryUnavailableException}.
 */
public interface CrmDirectory {

    int MAX_LOOKUP_IDS = 200;

    Map<UUID, AccountContext> accounts(Collection<UUID> accountIds);

    /**
     * Asks the CRM, with the agent's own access token, whether that agent may use the account.
     * The CRM applies its own scoped ownership check; false means it refused or does not know the
     * account. An unreachable CRM throws {@link DirectoryUnavailableException}.
     */
    boolean agentCanAccess(UUID accountId, String bearerToken);
}
