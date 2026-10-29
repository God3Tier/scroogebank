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
        Map<UUID, ClientContext> clients = crm.clients(refs.stream().map(UUID::fromString).toList());
        Timestamp now = Timestamp.from(clock.instant());
        for (String ref : refs) {
            ClientContext client = clients.get(UUID.fromString(ref));
            // A client the CRM no longer returns has no owner: nobody sees its alerts.
            jdbc.update("""
                    INSERT INTO client_assignment (client_ref, agent_subject, checked_at) VALUES (?, ?, ?)
                    ON CONFLICT (client_ref) DO UPDATE
                       SET agent_subject = EXCLUDED.agent_subject, checked_at = EXCLUDED.checked_at
                    """, ref, client == null ? null : client.assignedAgentSubject(), now);
        }
    }

    /** SQL predicate: the alert's current owner, falling back to its creation-time agent when never checked. */
    public static final String OWNER_EXPRESSION =
            "(CASE WHEN ca.client_ref IS NULL THEN a.assigned_agent_subject ELSE ca.agent_subject END)";
}
