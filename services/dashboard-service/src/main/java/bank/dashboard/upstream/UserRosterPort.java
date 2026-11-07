package bank.dashboard.upstream;

import bank.dashboard.upstream.dto.AgentSummary;

import java.util.List;

/**
 * The CRM user roster, owned by Feature 1's user-service. Needed to put names
 * against agent identifiers and to count active agents.
 */
public interface UserRosterPort {

    List<AgentSummary> agents();
}
