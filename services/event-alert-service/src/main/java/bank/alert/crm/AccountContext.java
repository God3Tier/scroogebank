package bank.alert.crm;

import java.util.Objects;
import java.util.UUID;

/** The minimal CRM facts about an account that the alert service may use. */
public record AccountContext(UUID accountId, UUID clientId, String type, String status, String currency) {

    public AccountContext {
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(clientId);
    }

    public boolean activeInvestment() {
        return "INVESTMENT".equalsIgnoreCase(type) && "ACTIVE".equalsIgnoreCase(status);
    }
}
