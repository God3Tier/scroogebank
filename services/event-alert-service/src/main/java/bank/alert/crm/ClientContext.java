package bank.alert.crm;

import java.util.Objects;
import java.util.UUID;

/** A CRM client's current assigned agent (the Cognito subject). */
public record ClientContext(UUID clientId, String assignedAgentSubject) {

    public ClientContext {
        Objects.requireNonNull(clientId);
    }
}
