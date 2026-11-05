package bank.alert.report;

import bank.alert.domain.AlertStatus;
import bank.alert.event.AgentEventModels.AffectedClient;
import bank.alert.event.AgentEventModels.EventDetail;
import bank.alert.event.AgentEventQueryService;
import bank.alert.service.AlertMutationConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What the engagement service needs to email one event report to chosen clients (ADR-0084): the current report,
 * its sources, and for each chosen alert only the client, instrument and issuer. Ownership and the report version
 * are checked again here, on every call. A client is released only when the alert is still the agent's, is a CRM
 * client, and the agent has marked it INFORMED. Nothing is sent or changed here.
 */
@Service
public class ReportPackageService {

    private static final Pattern CRM_CLIENT = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public record PackageSource(String id, String title, String url, String domain, Instant publishedAt,
            Instant retrievedAt, boolean usedInReport) {
    }

    /** {@code outcome} is OK, or NOT_AUTHORISED with the client fields left null. */
    public record PackageRecipient(UUID alertId, String outcome, String clientRef, String instrumentId, String issuer) {
    }

    public record ReportPackage(UUID eventId, UUID reportId, int version, String schemaVersion, JsonNode report,
            String reportHash, List<PackageSource> sources, List<PackageRecipient> recipients) {
    }

    private final JdbcTemplate jdbc;
    private final AgentEventQueryService events;
    private final ObjectMapper mapper;

    public ReportPackageService(JdbcTemplate jdbc, AgentEventQueryService events, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.events = events;
        this.mapper = mapper;
    }

    /**
     * @throws bank.alert.service.AlertNotFoundException        the event has no alert of this agent
     * @throws AlertMutationConflictException                   there is no report, or it is not at the expected version
     */
    @Transactional(readOnly = true)
    public ReportPackage build(UUID eventId, String agent, int expectedVersion, List<UUID> alertIds) {
        EventDetail detail = events.detail(eventId, agent);
        if (detail.report() == null || detail.report().version() != expectedVersion) {
            throw new AlertMutationConflictException("The report changed since you looked; reload and review it again");
        }
        UUID reportId = detail.report().reportId();
        List<String[]> stored = jdbc.query("SELECT report::text, source_ids::text, schema_version FROM event_report WHERE id = ?",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, reportId);
        if (stored.isEmpty()) throw new AlertMutationConflictException("The report is no longer available");
        String reportJson = stored.getFirst()[0];
        try {
            Set<String> used = new HashSet<>();
            mapper.readTree(stored.getFirst()[1]).forEach(id -> used.add(id.asText()));
            List<PackageSource> sources = jdbc.query("""
                    SELECT sr.id, sr.title, sr.canonical_url, sr.source_domain, sr.published_at, sr.retrieved_at
                      FROM public_event_source s JOIN source_result sr ON sr.id = s.source_result_id
                     WHERE s.public_event_id = ?
                     ORDER BY sr.published_at NULLS LAST, sr.canonical_url
                    """, (rs, n) -> new PackageSource(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                    rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                    used.contains(rs.getString(1))), eventId);
            List<PackageRecipient> recipients = new ArrayList<>();
            for (UUID alertId : new LinkedHashSet<>(alertIds)) {
                recipients.add(recipient(detail, alertId));
            }
            return new ReportPackage(eventId, reportId, detail.report().version(), stored.getFirst()[2],
                    mapper.readTree(reportJson), hash(reportJson, detail.report().version(), sources), sources, recipients);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("stored report unreadable", unreadable);
        }
    }

    private PackageRecipient recipient(EventDetail detail, UUID alertId) {
        AffectedClient client = detail.clients().stream().filter(c -> c.alertId().equals(alertId)).findFirst().orElse(null);
        if (client == null || client.status() != AlertStatus.INFORMED || !CRM_CLIENT.matcher(client.clientRef()).matches()) {
            return new PackageRecipient(alertId, "NOT_AUTHORISED", null, null, null);
        }
        String issuer = jdbc.queryForList("SELECT issuer_name FROM instrument WHERE id = ?", String.class,
                client.instrumentId()).stream().findFirst().orElse(client.instrumentId());
        return new PackageRecipient(alertId, "OK", client.clientRef(), client.instrumentId(), issuer);
    }

    private static String hash(String reportJson, int version, List<PackageSource> sources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((version + "|" + reportJson).getBytes(StandardCharsets.UTF_8));
            sources.stream().map(PackageSource::id).sorted().forEach(id -> digest.update(("|" + id).getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
