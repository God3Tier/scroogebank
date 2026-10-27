package bank.engagement.integration.identity;

import java.util.Optional;

/** Finds an agent by their Cognito {@code sub}. */
public interface AgentDirectory {

    /** Empty when the agent is unknown or the lookup failed. */
    Optional<AgentProfile> find(String sub);
}
