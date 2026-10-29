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
}
