package bank.engagement.integration.identity;

/**
 * An agent's address and display name from Cognito (ADR-0053 decision 4). The
 * address is used for one message at a time and never stored or logged.
 */
public record AgentProfile(String sub, String email, String displayName, boolean enabled) {

    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }

    @Override
    public String toString() {
        return "AgentProfile[sub=" + sub + ", enabled=" + enabled + "]";
    }
}
