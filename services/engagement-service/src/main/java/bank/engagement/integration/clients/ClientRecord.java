package bank.engagement.integration.clients;

import java.util.UUID;

/**
 * The few client fields this service uses, read for one request and never
 * stored, logged or sent to a model (ADR-0053 decision 5, ADR-0054 decision 2).
 */
public record ClientRecord(UUID clientId, String firstName, String email, String country) {

    @Override
    public String toString() {
        return "ClientRecord[clientId=" + clientId + "]";
    }
}
