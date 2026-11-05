package bank.alert.service;

import bank.alert.crm.AccountContext;
import bank.alert.crm.CrmDirectory;
import bank.alert.crm.CurrentOwnership;
import bank.alert.crm.DirectoryUnavailableException;
import bank.alert.fixture.SyntheticFixtureLoader;
import bank.alert.fixture.SyntheticHoldingFixture;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Which companies an agent's clients hold right now (ADR-0079).
 *
 * <p>In database mode the positions agents recorded are matched to their accounts, and the CRM
 * is asked who owns each account now, so a client with holdings but no alert yet still counts and
 * a reassignment moves the companies with the client (ADR-0066). In fixture mode it is the bundled
 * holdings file, the same source the alerts use, with a reassignment in {@code client_assignment}
 * taking precedence over the file's agent.
 */
@Component
public class AgentHeldInstruments {

    private final JdbcTemplate jdbc;
    private final SyntheticFixtureLoader fixtureLoader;
    private final ObjectProvider<CrmDirectory> directory;
    private final boolean databaseMode;

    public AgentHeldInstruments(JdbcTemplate jdbc, SyntheticFixtureLoader fixtureLoader,
            ObjectProvider<CrmDirectory> directory, @Value("${alert.holdings.mode:fixture}") String holdingsMode) {
        this.jdbc = jdbc;
        this.fixtureLoader = fixtureLoader;
        this.directory = directory;
        this.databaseMode = "database".equalsIgnoreCase(holdingsMode);
    }

    public List<String> idsFor(String agentSubject) {
        return databaseMode ? fromRecordedPositions(agentSubject) : fromFixture(agentSubject);
    }

    public boolean holds(String agentSubject, String instrumentId) {
        return idsFor(agentSubject).contains(instrumentId);
    }

    private List<String> fromRecordedPositions(String agentSubject) {
        CrmDirectory crm = directory.getIfAvailable();
        if (crm == null) throw new DirectoryUnavailableException("the CRM directory is not configured");
        Map<UUID, Set<String>> instrumentsByAccount = new HashMap<>();
        jdbc.query("SELECT account_id, instrument_id FROM current_position",
                rs -> { instrumentsByAccount.computeIfAbsent(UUID.fromString(rs.getString(1)), id -> new TreeSet<>())
                        .add(rs.getString(2)); });
        if (instrumentsByAccount.size() > CurrentOwnership.MAX_REFERENCED_CLIENTS) {
            throw new DirectoryUnavailableException("too many accounts to verify ownership");
        }
        List<UUID> accountIds = new ArrayList<>(instrumentsByAccount.keySet());
        Set<String> held = new TreeSet<>();
        for (int from = 0; from < accountIds.size(); from += CrmDirectory.MAX_LOOKUP_IDS) {
            List<UUID> chunk = accountIds.subList(from, Math.min(accountIds.size(), from + CrmDirectory.MAX_LOOKUP_IDS));
            for (Map.Entry<UUID, AccountContext> entry : crm.accounts(chunk).entrySet()) {
                AccountContext account = entry.getValue();
                if (account.ownedBy(agentSubject) && account.activeInvestment()) {
                    held.addAll(instrumentsByAccount.getOrDefault(entry.getKey(), Set.of()));
                }
            }
        }
        return List.copyOf(held);
    }

    private List<String> fromFixture(String agentSubject) {
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
}
