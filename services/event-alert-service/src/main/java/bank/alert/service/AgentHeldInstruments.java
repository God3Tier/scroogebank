package bank.alert.service;

import bank.alert.fixture.SyntheticFixtureLoader;
import bank.alert.fixture.SyntheticHoldingFixture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which companies an agent's clients hold right now (ADR-0079). In database mode that is the
 * current position of each account whose client the agent currently owns (ADR-0066); in fixture
 * mode it is the bundled holdings file, the same source the alerts use, with a reassignment in
 * {@code client_assignment} taking precedence over the file's agent. Callers refresh
 * {@code CurrentOwnership} first.
 */
@Component
public class AgentHeldInstruments {

    private static final String DATABASE_MODE = """
            SELECT DISTINCT cpos.instrument_id
              FROM current_position cpos
              JOIN current_portfolio port ON port.account_id = cpos.account_id
              JOIN client_assignment ca ON ca.client_ref = lower(port.client_id::text)
             WHERE ca.agent_subject = ?
            """;

    private final JdbcTemplate jdbc;
    private final SyntheticFixtureLoader fixtureLoader;
    private final boolean databaseMode;

    public AgentHeldInstruments(JdbcTemplate jdbc, SyntheticFixtureLoader fixtureLoader,
            @Value("${alert.holdings.mode:fixture}") String holdingsMode) {
        this.jdbc = jdbc;
        this.fixtureLoader = fixtureLoader;
        this.databaseMode = "database".equalsIgnoreCase(holdingsMode);
    }

    public List<String> idsFor(String agentSubject) {
        if (databaseMode) return jdbc.queryForList(DATABASE_MODE, String.class, agentSubject);
        Map<String, String> reassigned = new HashMap<>();
        jdbc.query("SELECT client_ref, agent_subject FROM client_assignment",
                rs -> { reassigned.put(rs.getString(1), rs.getString(2)); });
        return fixtureLoader.loadDefault().positions().stream()
                .filter(position -> position.quantity().signum() > 0)
                .filter(position -> agentSubject.equals(
                        reassigned.getOrDefault(position.clientRef(), position.assignedAgentSubject())))
                .map(SyntheticHoldingFixture.Position::instrumentId)
                .distinct()
                .sorted()
                .toList();
    }

    public boolean holds(String agentSubject, String instrumentId) {
        return idsFor(agentSubject).contains(instrumentId);
    }
}
