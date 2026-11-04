package bank.alert.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Which companies an agent's clients hold right now (ADR-0079). In database mode that is the
 * current position of each account whose client the agent currently owns (ADR-0066); in fixture
 * mode it is the bundled holding snapshots. Callers refresh {@code CurrentOwnership} first.
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

    private static final String FIXTURE_MODE = """
            SELECT DISTINCT h.instrument_id
              FROM holding_snapshot h
              LEFT JOIN client_assignment ca ON ca.client_ref = h.client_ref
             WHERE (CASE WHEN ca.client_ref IS NULL THEN h.assigned_agent_subject
                         ELSE ca.agent_subject END) = ?
            """;

    private final JdbcTemplate jdbc;
    private final boolean databaseMode;

    public AgentHeldInstruments(JdbcTemplate jdbc, @Value("${alert.holdings.mode:fixture}") String holdingsMode) {
        this.jdbc = jdbc;
        this.databaseMode = "database".equalsIgnoreCase(holdingsMode);
    }

    public List<String> idsFor(String agentSubject) {
        return jdbc.queryForList(databaseMode ? DATABASE_MODE : FIXTURE_MODE, String.class, agentSubject);
    }

    public boolean holds(String agentSubject, String instrumentId) {
        return idsFor(agentSubject).contains(instrumentId);
    }
}
