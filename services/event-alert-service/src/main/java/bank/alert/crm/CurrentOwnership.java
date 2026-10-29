package bank.alert.crm;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps a minimal copy of "who owns this CRM client right now" so alert visibility
 * follows reassignments (ADR-0066). It is refreshed before every protected read or
 * mutation and is never trusted as a long-lived positive cache. If the CRM cannot be
 * asked, or more clients are referenced than the bounded demo population allows,
 * the caller fails rather than using a stale or truncated authorisation set.
 *
 * <p>Inactive in fixture mode, where no CRM directory exists.
 */
@Service
public class CurrentOwnership {

    public static final int MAX_REFERENCED_CLIENTS = 1000;

    private static final String CRM_UUID = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final JdbcTemplate jdbc;
    private final ObjectProvider<CrmDirectory> directory;
    private final Clock clock;

    public CurrentOwnership(JdbcTemplate jdbc, ObjectProvider<CrmDirectory> directory, Clock clock) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.clock = clock;
    }

    @Transactional
    public void refresh() {
        CrmDirectory crm = directory.getIfAvailable();
        if (crm == null) return;
        List<String> refs = jdbc.queryForList(
                "SELECT DISTINCT client_ref FROM alert WHERE client_ref ~ '" + CRM_UUID + "'", String.class);
        if (refs.isEmpty()) return;
        if (refs.size() > MAX_REFERENCED_CLIENTS) {
            throw new DirectoryUnavailableException("too many referenced clients to verify ownership");
        }
        // Each client is asked about through its recorded accounts. All of them must agree on the
        // owner; a client with no account the CRM still returns, or with disagreeing owners, has
        // no owner and nobody sees its alerts.
        List<Map<String, Object>> links = jdbc.queryForList(
                "SELECT client_id, account_id FROM current_portfolio WHERE client_id::text IN ("
                        + String.join(",", java.util.Collections.nCopies(refs.size(), "?")) + ")",
                refs.toArray());
        Map<UUID, AccountContext> accounts = crm.accounts(
                links.stream().map(row -> (UUID) row.get("account_id")).toList());
        Map<String, java.util.Set<String>> owners = new java.util.HashMap<>();
        for (Map<String, Object> link : links) {
            UUID clientId = (UUID) link.get("client_id");
            AccountContext account = accounts.get((UUID) link.get("account_id"));
            if (account != null && account.clientId().equals(clientId) && account.ownerAgentSubject() != null
                    && !account.ownerAgentSubject().isBlank()) {
                owners.computeIfAbsent(clientId.toString(), key -> new java.util.HashSet<>())
                        .add(account.ownerAgentSubject());
            }
        }
        Timestamp now = Timestamp.from(clock.instant());
        for (String ref : refs) {
            java.util.Set<String> agreed = owners.getOrDefault(ref, java.util.Set.of());
            String owner = agreed.size() == 1 ? agreed.iterator().next() : null;
            jdbc.update("""
                    INSERT INTO client_assignment (client_ref, agent_subject, checked_at) VALUES (?, ?, ?)
                    ON CONFLICT (client_ref) DO UPDATE
                       SET agent_subject = EXCLUDED.agent_subject, checked_at = EXCLUDED.checked_at
                    """, ref, owner, now);
        }
    }

    /** SQL predicate: the alert's current owner, falling back to its creation-time agent when never checked. */
    public static final String OWNER_EXPRESSION =
            "(CASE WHEN ca.client_ref IS NULL THEN a.assigned_agent_subject ELSE ca.agent_subject END)";
}
