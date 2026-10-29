package bank.alert.crm;

import java.util.Objects;
import java.util.UUID;

/**
 * The minimal CRM facts about an account that the alert service may use. The owner is the
 * agent who owns the account now; the CRM moves it with the client on reassignment, so it
 * is the client's current agent (ADR-0060).
 */
public record AccountContext(UUID accountId, UUID clientId, String type, String status, String currency,
        String ownerAgentSubject) {

    public AccountContext {
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(clientId);
    }

    public boolean ownedBy(String agentSubject) {
        return ownerAgentSubject != null && !ownerAgentSubject.isBlank() && ownerAgentSubject.equals(agentSubject);
    }

    public boolean activeInvestment() {
        return "INVESTMENT".equalsIgnoreCase(type) && "ACTIVE".equalsIgnoreCase(status);
    }
}
