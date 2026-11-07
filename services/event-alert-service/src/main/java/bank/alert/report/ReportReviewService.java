package bank.alert.report;

import bank.alert.config.ReportReviewProperties;
import bank.alert.crm.CurrentOwnership;
import bank.alert.domain.Alert;
import bank.alert.domain.AlertStatus;
import bank.alert.domain.AuditOutbox;
import bank.alert.event.AgentEventModels.EventDetail;
import bank.alert.event.AgentEventQueryService;
import bank.alert.repository.AlertRepository;
import bank.alert.repository.AuditOutboxRepository;
import bank.alert.service.AlertMutationConflictException;
import bank.alert.service.AlertNotFoundException;
import bank.alert.service.AlertStateMachine;
import bank.alert.service.AuditUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Editing, approving and handing over reports for news emails (news-alert-drafts plan, sections 5 to 7).
 *
 * <p>An agent may edit a report's wording, then approves it. Approval is final and freezes the text the client
 * receives. engagement-service builds the email from these approvals: it reads each client's approved report
 * ({@link #packageFor}), checks everything again before sending ({@link #sendCheck}), fetches the combined PDF
 * ({@link #combinedPdf}) and reports the send back ({@link #sentOut}). Every call uses the agent's own token, and
 * every check is made again on the server.
 */
@Service
public class ReportReviewService {

    private static final Pattern CRM_CLIENT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern CITATION = Pattern.compile("\\s*\\[(?:\\d+|src-[^\\]]*)\\]");
    private static final String OWNER = CurrentOwnership.OWNER_EXPRESSION;
    static final int MAX_DOCUMENT_REPORTS = 50;
    private static final List<String> BACKLOG_STATUSES = List.of("PENDING", "FAILED");

    // ---- views ----------------------------------------------------------------------------------------------------

    /** The agent's approval of one report version. {@code valid} is false once it expired or its text was cleared. */
    public record ApprovalView(UUID approvalId, UUID eventId, int version, boolean edited, Instant approvedAt,
            Instant validUntil, boolean valid, boolean stale) {
    }

    /**
     * What the Event Alerts page needs to edit and approve the latest report: the agent's saved edit (the sections in
     * the edit request's shape, or null), the original sections, the agent's approval of this version, if any, and
     * the approved text while it is kept.
     */
    public record ReviewView(UUID eventId, int reportVersion, boolean editable, JsonNode originalSections,
            JsonNode editedSections, ApprovalView approval, JsonNode approvedSections, Instant newsDate) {
    }

    /** {@code outcome}: OK, NOT_AUTHORISED, NOT_INFORMED or NOT_APPROVED. The other fields are null unless OK. */
    public record PackageItem(UUID alertId, String outcome, UUID approvalId, String clientRef, String instrumentId,
            String issuer, String headline, Instant newsDate, Instant validUntil, boolean edited, String finalHash,
            String summaryLine) {
    }

    public record ApprovedItem(UUID alertId, String clientRef, UUID eventId, UUID approvalId, String instrumentId,
            String ticker, String issuer, String headline, Instant newsDate, Instant approvedAt, Instant validUntil,
            boolean edited) {
    }

    /** {@code outcome}: OK, NOT_AUTHORISED, NOT_INFORMED, NOT_APPROVED or EXPIRED. */
    public record CheckItem(UUID alertId, String outcome, UUID approvalId, String finalHash) {
    }

    /** {@code outcome}: SENT_OUT, ALREADY_SENT_OUT, NOT_AUTHORISED or INVALID_STATUS. */
    public record SentOutItem(UUID alertId, String outcome) {
    }

    public record SentOutRow(UUID alertId, UUID eventId, String clientRef, String instrumentId, String ticker,
            String issuer, String headline, int reportVersion, boolean edited, Instant sentAt) {
    }

    public record DocumentSection(UUID approvalId, String issuer, String headline, int startPage, String finalHash) {
    }

    public record PdfBundle(String fileName, LocalDate documentDate, String pdfBase64, String pdfHash, int pageCount,
            List<DocumentSection> sections) {
    }

    public record SinglePdf(String fileName, byte[] pdf) {
    }

    // ---- collaborators --------------------------------------------------------------------------------------------

    private final JdbcTemplate jdbc;
    private final AgentEventQueryService events;
    private final AlertRepository alerts;
    private final AlertStateMachine stateMachine;
    private final CurrentOwnership ownership;
    private final AuditOutboxRepository outbox;
    private final ObjectMapper mapper;
    private final ReportReviewProperties properties;
    private final Clock clock;
    private final ReportPdfRenderer renderer = new ReportPdfRenderer();
    private final int maxPendingAuditEvents;

    public ReportReviewService(JdbcTemplate jdbc, AgentEventQueryService events, AlertRepository alerts,
            AlertStateMachine stateMachine, CurrentOwnership ownership, AuditOutboxRepository outbox,
            ObjectMapper mapper, ReportReviewProperties properties, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${alert.audit.max-pending:10000}") int maxPendingAuditEvents) {
        this.jdbc = jdbc;
        this.events = events;
        this.alerts = alerts;
        this.stateMachine = stateMachine;
        this.ownership = ownership;
        this.outbox = outbox;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
        this.maxPendingAuditEvents = maxPendingAuditEvents;
    }

    // ---- edit and approve (Event Alerts page) ---------------------------------------------------------------------

    @Transactional
    public ReviewView review(UUID eventId, String agent) {
        EventDetail detail = events.detail(eventId, agent);
        StoredReport report = latestReport(eventId).orElse(null);
        if (detail.report() == null || report == null) {
            return new ReviewView(eventId, 0, false, null, null, null, null, null);
        }
        JsonNode edit = jdbc.query("SELECT sections::text FROM report_edit WHERE event_report_id = ? AND agent_sub = ?",
                (rs, n) -> read(rs.getString(1)), report.id(), agent).stream().findFirst().orElse(null);
        boolean editable = ReportEdits.editable(report.schemaVersion());
        Optional<StoredApproval> approved = approvalForReport(report.id(), agent);
        JsonNode approvedSections = approved.map(StoredApproval::text).filter(java.util.Objects::nonNull)
                .map(text -> (JsonNode) ReportEdits.sectionsOf(text, mapper)).orElse(null);
        return new ReviewView(eventId, report.version(), editable,
                editable ? ReportEdits.sectionsOf(report.json(), mapper) : null, edit,
                approved.map(this::view).orElse(null), approvedSections, newsDate(report));
    }

    /**
     * Saves the agent's edit of the latest version, after the checks of plan section 5.3. Nothing is saved when the
     * text fails a check.
     */
    @Transactional
    public ReviewView saveEdit(UUID eventId, String agent, int expectedVersion, JsonNode sections) {
        events.detail(eventId, agent);
        StoredReport report = currentReport(eventId, expectedVersion);
        if (!ReportEdits.editable(report.schemaVersion())) {
            throw new AlertMutationConflictException("This older report cannot be edited");
        }
        Optional<ApprovalView> approval = approvalOf(report.id(), agent);
        if (approval.isPresent() && approval.get().valid()) {
            throw new AlertMutationConflictException("You approved this report; an approved report cannot be edited");
        }
        ObjectNode edited = ReportEdits.merge(report.json(), sections);
        ReportEdits.Violation violation = ReportEdits.check(report.json(), edited, sourceTexts(report));
        if (violation != null) throw new ReportEdits.EditRefused(violation);
        ensureAuditCapacity();
        JsonNode stored = ReportEdits.sectionsOf(edited, mapper);
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO report_edit (event_report_id, agent_sub, version, sections, updated_at)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (event_report_id, agent_sub) DO UPDATE
                   SET sections = EXCLUDED.sections, updated_at = EXCLUDED.updated_at
                """, report.id(), agent, report.version(), json(stored), Timestamp.from(now));
        List<String> changed = changedSections(ReportEdits.sectionsOf(report.json(), mapper), stored);
        audit("alert.report-edited", Map.of("publicEventId", eventId, "reportId", report.id(),
                "reportVersion", report.version(), "agentSubject", agent, "sectionsChanged", changed,
                "editHash", sha256(json(stored)), "occurredAt", now.toString()), now);
        return review(eventId, agent);
    }

    /** Throws the agent's unapproved edit away, so the original wording is used. */
    @Transactional
    public ReviewView discardEdit(UUID eventId, String agent) {
        events.detail(eventId, agent);
        latestReport(eventId).ifPresent(report ->
                jdbc.update("DELETE FROM report_edit WHERE event_report_id = ? AND agent_sub = ?", report.id(), agent));
        return review(eventId, agent);
    }

    /**
     * Approves the latest version with the agent's saved edit, if any. Approval is final. At least one of the agent's
     * clients on the event must be in Plan client briefing. An expired approval can be renewed ("Approve again").
     */
    @Transactional
    public ApprovalView approve(UUID eventId, String agent, int expectedVersion) {
        EventDetail detail = events.detail(eventId, agent);
        StoredReport report = currentReport(eventId, expectedVersion);
        if (detail.clients().stream().noneMatch(client -> client.status() == AlertStatus.INFORMED)) {
            throw new AlertMutationConflictException("Choose Plan client briefing for at least one client before approving");
        }
        Optional<ApprovalView> existing = approvalOf(report.id(), agent);
        if (existing.isPresent() && existing.get().valid()) {
            throw new AlertMutationConflictException("You already approved this report");
        }
        ensureAuditCapacity();
        JsonNode editSections = jdbc.query(
                "SELECT sections::text FROM report_edit WHERE event_report_id = ? AND agent_sub = ?",
                (rs, n) -> read(rs.getString(1)), report.id(), agent).stream().findFirst().orElse(null);
        JsonNode finalText = report.json();
        if (editSections != null && ReportEdits.editable(report.schemaVersion())) {
            ObjectNode merged = ReportEdits.merge(report.json(), editSections);
            ReportEdits.Violation violation = ReportEdits.check(report.json(), merged, sourceTexts(report));
            if (violation != null) throw new ReportEdits.EditRefused(violation);
            finalText = merged;
        }
        String finalJson = json(finalText);
        boolean edited = !finalText.equals(report.json());
        String originalHash = sha256(report.version() + "|" + json(report.json()));
        String finalHash = sha256(report.version() + "|" + finalJson);
        Instant now = clock.instant();
        Instant validUntil = now.plus(properties.approvalValidFor());
        UUID approvalId = existing.map(ApprovalView::approvalId).orElse(UUID.randomUUID());
        if (existing.isPresent()) {
            jdbc.update("""
                    UPDATE approved_report SET final_text = CAST(? AS jsonb), edited = ?, original_hash = ?,
                           final_hash = ?, approved_at = ?, valid_until = ?, text_cleared_at = NULL WHERE id = ?
                    """, finalJson, edited, originalHash, finalHash, Timestamp.from(now), Timestamp.from(validUntil),
                    approvalId);
        } else {
            jdbc.update("""
                    INSERT INTO approved_report (id, event_report_id, public_event_id, version, agent_sub, final_text,
                                                 edited, original_hash, final_hash, approved_at, valid_until)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                    """, approvalId, report.id(), eventId, report.version(), agent, finalJson, edited, originalHash,
                    finalHash, Timestamp.from(now), Timestamp.from(validUntil));
        }
        jdbc.update("DELETE FROM report_edit WHERE event_report_id = ? AND agent_sub = ?", report.id(), agent);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", approvalId);
        payload.put("publicEventId", eventId);
        payload.put("reportId", report.id());
        payload.put("reportVersion", report.version());
        payload.put("agentSubject", agent);
        payload.put("edited", edited);
        payload.put("originalHash", originalHash);
        payload.put("finalHash", finalHash);
        payload.put("validUntil", validUntil.toString());
        payload.put("occurredAt", now.toString());
        audit("alert.report-approved", payload, now);
        return approvalOf(report.id(), agent).orElseThrow();
    }

    // ---- engagement-service ---------------------------------------------------------------------------------------

    /**
     * For a news draft: each chosen client's approved report. A client is released only when the alert is still the
     * agent's, is on this event, is a CRM client and is in Plan client briefing, and the agent holds a valid approval.
     */
    @Transactional
    public List<PackageItem> packageFor(String agent, UUID eventId, List<UUID> alertIds) {
        ownership.refresh();
        List<PackageItem> items = new ArrayList<>();
        Optional<StoredApproval> approval = validApproval(eventId, agent);
        for (UUID alertId : new LinkedHashSet<>(alertIds)) {
            Alert alert = alerts.findOwnedByCurrentAgent(alertId, agent).orElse(null);
            if (alert == null || !eventId.equals(alert.getPublicEventId()) || !CRM_CLIENT.matcher(alert.getClientRef()).matches()) {
                items.add(refusedItem(alertId, "NOT_AUTHORISED"));
            } else if (alert.getStatus() != AlertStatus.INFORMED) {
                items.add(refusedItem(alertId, "NOT_INFORMED"));
            } else if (approval.isEmpty()) {
                items.add(refusedItem(alertId, "NOT_APPROVED"));
            } else {
                StoredApproval a = approval.get();
                Issuer issuer = issuerOf(eventId);
                items.add(new PackageItem(alertId, "OK", a.id(), alert.getClientRef(), alert.getInstrumentId(),
                        issuerName(alert.getInstrumentId(), issuer), a.text().path("headline").asText(),
                        a.newsDate(), a.validUntil(), a.edited(), a.finalHash(), summaryLine(a)));
            }
        }
        return items;
    }

    /** The alternate entry on Follow-up Emails: the caller's approved, unsent reports, for one client or all. */
    @Transactional
    public List<ApprovedItem> approved(String agent, String clientRef) {
        ownership.refresh();
        StringBuilder sql = new StringBuilder("""
                SELECT a.id AS alert_id, a.client_ref, a.public_event_id, a.instrument_id, ins.ticker, ins.issuer_name,
                       ar.id AS approval_id
                  FROM alert a
                  LEFT JOIN client_assignment ca ON ca.client_ref = a.client_ref
                  JOIN approved_report ar ON ar.public_event_id = a.public_event_id AND ar.agent_sub = ?
                       AND ar.final_text IS NOT NULL AND ar.valid_until > ?
                  JOIN instrument ins ON ins.id = a.instrument_id
                 WHERE %s = ? AND a.status = 'INFORMED'
                   AND ar.version = (SELECT max(ar2.version) FROM approved_report ar2
                                      WHERE ar2.public_event_id = a.public_event_id AND ar2.agent_sub = ?
                                        AND ar2.final_text IS NOT NULL AND ar2.valid_until > ?)
                """.formatted(OWNER));
        Timestamp now = Timestamp.from(clock.instant());
        List<Object> args = new ArrayList<>(List.of(agent, now, agent, agent, now));
        if (clientRef != null && !clientRef.isBlank()) {
            sql.append(" AND a.client_ref = ?");
            args.add(clientRef);
        }
        sql.append(" ORDER BY a.client_ref, ins.issuer_name, a.id");
        List<Object[]> rows = jdbc.query(sql.toString(), (rs, n) -> new Object[] {
                rs.getObject("alert_id", UUID.class), rs.getString("client_ref"), rs.getObject("public_event_id", UUID.class),
                rs.getString("instrument_id"), rs.getString("ticker"), rs.getString("issuer_name"),
                rs.getObject("approval_id", UUID.class)}, args.toArray());
        List<ApprovedItem> items = new ArrayList<>();
        for (Object[] row : rows) {
            if (!CRM_CLIENT.matcher((String) row[1]).matches()) continue;
            StoredApproval approval = approvalById((UUID) row[6]).orElse(null);
            if (approval == null) continue;
            items.add(new ApprovedItem((UUID) row[0], (String) row[1], (UUID) row[2], approval.id(), (String) row[3],
                    (String) row[4], (String) row[5], approval.text().path("headline").asText(), approval.newsDate(),
                    approval.approvedAt(), approval.validUntil(), approval.edited()));
        }
        return items;
    }

    /** The check made just before sending (plan 5.6): each report must still be sendable. */
    @Transactional
    public List<CheckItem> sendCheck(String agent, List<UUID> alertIds) {
        ownership.refresh();
        List<CheckItem> items = new ArrayList<>();
        for (UUID alertId : new LinkedHashSet<>(alertIds)) {
            Alert alert = alerts.findOwnedByCurrentAgent(alertId, agent).orElse(null);
            if (alert == null || !CRM_CLIENT.matcher(alert.getClientRef()).matches()) {
                items.add(new CheckItem(alertId, "NOT_AUTHORISED", null, null));
                continue;
            }
            if (alert.getStatus() != AlertStatus.INFORMED) {
                items.add(new CheckItem(alertId, "NOT_INFORMED", null, null));
                continue;
            }
            Optional<StoredApproval> valid = validApproval(alert.getPublicEventId(), agent);
            if (valid.isPresent()) {
                items.add(new CheckItem(alertId, "OK", valid.get().id(), valid.get().finalHash()));
            } else {
                boolean any = jdbc.queryForObject("SELECT count(*) FROM approved_report WHERE public_event_id = ? AND agent_sub = ?",
                        Integer.class, alert.getPublicEventId(), agent) > 0;
                items.add(new CheckItem(alertId, any ? "EXPIRED" : "NOT_APPROVED", null, null));
            }
        }
        return items;
    }

    /** After SES accepted the email: the clients move to Sent Out. Repeating the call changes nothing. */
    @Transactional
    public List<SentOutItem> sentOut(String agent, List<UUID> alertIds, UUID draftId, String sesMessageId) {
        ownership.refresh();
        ensureAuditCapacity();
        Instant now = clock.instant();
        List<SentOutItem> items = new ArrayList<>();
        List<UUID> moved = new ArrayList<>();
        List<UUID> approvalIds = new ArrayList<>();
        for (UUID alertId : new LinkedHashSet<>(alertIds)) {
            Alert alert = alerts.findOwnedByCurrentAgent(alertId, agent).orElse(null);
            if (alert == null) {
                items.add(new SentOutItem(alertId, "NOT_AUTHORISED"));
                continue;
            }
            if (alert.getStatus() == AlertStatus.SENT_OUT) {
                items.add(new SentOutItem(alertId, "ALREADY_SENT_OUT"));
                continue;
            }
            if (!stateMachine.canSendOut(alert.getStatus())) {
                items.add(new SentOutItem(alertId, "INVALID_STATUS"));
                continue;
            }
            StoredApproval approval = latestApproval(alert.getPublicEventId(), agent).orElse(null);
            alert.transitionTo(AlertStatus.SENT_OUT);
            alerts.saveAndFlush(alert);
            jdbc.update("""
                    INSERT INTO alert_sent_out (alert_id, approval_id, draft_id, ses_message_id, sent_at, recorded_by)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (alert_id) DO NOTHING
                    """, alertId, approval == null ? null : approval.id(), draftId, sesMessageId, Timestamp.from(now), agent);
            if (approval != null) approvalIds.add(approval.id());
            moved.add(alertId);
            items.add(new SentOutItem(alertId, "SENT_OUT"));
        }
        if (!moved.isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("alertIds", moved);
            payload.put("approvalIds", approvalIds.stream().distinct().toList());
            payload.put("draftId", draftId);
            payload.put("sesMessageId", sesMessageId);
            payload.put("agentSubject", agent);
            payload.put("occurredAt", now.toString());
            audit("alert.sent-out", payload, now);
            // An approval's text is kept only until every client on it is Sent Out (plan 7.1, retention).
            for (UUID approvalId : approvalIds.stream().distinct().toList()) {
                jdbc.update("""
                        UPDATE approved_report ar SET final_text = NULL, text_cleared_at = ?
                         WHERE ar.id = ? AND ar.final_text IS NOT NULL
                           AND NOT EXISTS (SELECT 1 FROM alert a LEFT JOIN client_assignment ca ON ca.client_ref = a.client_ref
                                            WHERE a.public_event_id = ar.public_event_id AND %s = ar.agent_sub
                                              AND a.status <> 'SENT_OUT')
                        """.formatted(OWNER), Timestamp.from(now), approvalId);
            }
        }
        return items;
    }

    /** The Sent out view: the caller's clients, or everyone's for an admin. Newest first. */
    @Transactional
    public List<SentOutRow> sentOutList(String agent, boolean admin, int limit) {
        if (!admin) ownership.refresh();
        String where = admin ? "TRUE" : OWNER + " = ?";
        List<Object> args = new ArrayList<>();
        if (!admin) args.add(agent);
        args.add(limit);
        return jdbc.query("""
                SELECT a.id, a.public_event_id, a.client_ref, a.instrument_id, ins.ticker, ins.issuer_name,
                       so.sent_at, ar.edited, ar.version,
                       (SELECT r.report->>'headline' FROM event_report r WHERE r.id = ar.event_report_id) AS headline
                  FROM alert a
                  JOIN alert_sent_out so ON so.alert_id = a.id
                  LEFT JOIN approved_report ar ON ar.id = so.approval_id
                  LEFT JOIN client_assignment ca ON ca.client_ref = a.client_ref
                  JOIN instrument ins ON ins.id = a.instrument_id
                 WHERE a.status = 'SENT_OUT' AND %s
                 ORDER BY so.sent_at DESC, a.id
                 LIMIT ?
                """.formatted(where), (rs, n) -> new SentOutRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(10),
                rs.getInt(9), rs.getBoolean(8), rs.getTimestamp(7).toInstant()), args.toArray());
    }

    // ---- PDFs -----------------------------------------------------------------------------------------------------

    /**
     * The combined PDF for a news draft: one section per approval, in issuer order. The same approvals and name always
     * give the same file. Every approval must be the caller's and still hold its text.
     */
    @Transactional(readOnly = true)
    public PdfBundle combinedPdf(String agent, List<UUID> approvalIds, String preparedBy) {
        List<UUID> distinct = new ArrayList<>(new LinkedHashSet<>(approvalIds));
        if (distinct.isEmpty() || distinct.size() > MAX_DOCUMENT_REPORTS) {
            throw new IllegalArgumentException("between 1 and " + MAX_DOCUMENT_REPORTS + " approvals");
        }
        List<StoredApproval> approvals = new ArrayList<>();
        for (UUID id : distinct) {
            StoredApproval approval = approvalById(id).orElseThrow(AlertNotFoundException::new);
            if (!approval.agent().equals(agent)) throw new AlertNotFoundException();
            approvals.add(approval);
        }
        List<Object[]> ordered = new ArrayList<>();
        for (StoredApproval approval : approvals) {
            Issuer issuer = issuerOf(approval.eventId());
            ordered.add(new Object[] {approval, issuer});
        }
        ordered.sort(Comparator.<Object[], String>comparing(o -> ((Issuer) o[1]).name().toLowerCase(java.util.Locale.ROOT))
                .thenComparing(o -> ((StoredApproval) o[0]).text().path("headline").asText())
                .thenComparing(o -> ((StoredApproval) o[0]).id()));
        String name = preparedBy == null || preparedBy.isBlank() ? null : preparedBy.strip();
        List<ReportPdfRenderer.Section> sections = new ArrayList<>();
        Instant newest = Instant.EPOCH;
        for (Object[] entry : ordered) {
            StoredApproval approval = (StoredApproval) entry[0];
            Issuer issuer = (Issuer) entry[1];
            if (approval.approvedAt().isAfter(newest)) newest = approval.approvedAt();
            sections.add(new ReportPdfRenderer.Section(issuer.name(), issuer.ticker(),
                    disclosure(approval.edited(), name), approval.text(), sourcesOf(approval.reportId())));
        }
        LocalDate date = ReportPdfRenderer.singaporeDate(newest);
        ReportPdfRenderer.Rendered rendered = renderer.render(new ReportPdfRenderer.Document(date, name, sections));
        List<DocumentSection> documentSections = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            StoredApproval approval = (StoredApproval) ordered.get(i)[0];
            documentSections.add(new DocumentSection(approval.id(), ((Issuer) ordered.get(i)[1]).name(),
                    approval.text().path("headline").asText(), rendered.startPages().get(i), approval.finalHash()));
        }
        return new PdfBundle(ReportPdfRenderer.fileName(date), date, Base64.getEncoder().encodeToString(rendered.pdf()),
                sha256(rendered.pdf()), rendered.pageCount(), documentSections);
    }

    /**
     * One report as a PDF, with no approval needed: for reading before approving, and for admins. The agent's valid
     * approval of this version is used when there is one; otherwise the original text, marked as not yet reviewed.
     */
    @Transactional
    public SinglePdf singlePdf(UUID eventId, Integer version, String agent, boolean admin) {
        if (!admin) events.detail(eventId, agent);
        StoredReport report = (version == null ? latestReport(eventId) : reportVersion(eventId, version))
                .orElseThrow(AlertNotFoundException::new);
        Issuer issuer = issuerOf(eventId);
        JsonNode text = report.json();
        String disclosure = "AI-drafted from public sources. Not yet reviewed.";
        LocalDate date = ReportPdfRenderer.singaporeDate(report.createdAt());
        if (!admin) {
            Optional<StoredApproval> approval = approvalForReport(report.id(), agent)
                    .filter(a -> a.validUntil().isAfter(clock.instant()));
            if (approval.isPresent()) {
                text = approval.get().text();
                disclosure = disclosure(approval.get().edited(), null);
                date = ReportPdfRenderer.singaporeDate(approval.get().approvedAt());
            } else if (ReportEdits.editable(report.schemaVersion())) {
                // Not approved yet: the agent's saved edit, so what they downloaded is what they wrote.
                List<Object[]> saved = jdbc.query(
                        "SELECT sections::text, updated_at FROM report_edit WHERE event_report_id = ? AND agent_sub = ?",
                        (rs, n) -> new Object[] {rs.getString(1), rs.getTimestamp(2).toInstant()}, report.id(), agent);
                if (!saved.isEmpty()) {
                    text = ReportEdits.merge(report.json(), read((String) saved.getFirst()[0]));
                    disclosure = "AI-drafted from public sources and edited by your relationship manager. Not yet approved.";
                    date = ReportPdfRenderer.singaporeDate((Instant) saved.getFirst()[1]);
                }
            }
        }
        ReportPdfRenderer.Rendered rendered = renderer.render(new ReportPdfRenderer.Document(date, null,
                List.of(new ReportPdfRenderer.Section(issuer.name(), issuer.ticker(), disclosure, text, sourcesOf(report.id())))));
        return new SinglePdf(ReportPdfRenderer.fileName(date), rendered.pdf());
    }

    static String disclosure(boolean edited, String agentName) {
        String who = agentName == null ? "your relationship manager" : "your relationship manager, " + agentName;
        return edited ? "AI-drafted from public sources and edited by " + who + "."
                : "AI-drafted from public sources and reviewed by " + who + ".";
    }

    // ---- reading --------------------------------------------------------------------------------------------------

    private record StoredReport(UUID id, UUID eventId, int version, String schemaVersion, JsonNode json,
            List<String> sourceIds, Instant createdAt, String summarySentence) {
    }

    private record StoredApproval(UUID id, UUID reportId, UUID eventId, int version, String agent, JsonNode text,
            boolean edited, String finalHash, Instant approvedAt, Instant validUntil, Instant newsDate,
            String summarySentence) {
    }

    private record Issuer(String name, String ticker) {
    }

    private StoredReport currentReport(UUID eventId, int expectedVersion) {
        StoredReport report = latestReport(eventId).orElse(null);
        if (report == null || report.version() != expectedVersion) {
            throw new AlertMutationConflictException("The report changed since you looked; reload and review it again");
        }
        return report;
    }

    private Optional<StoredReport> latestReport(UUID eventId) {
        return jdbc.query("""
                SELECT id, public_event_id, version, schema_version, report::text, source_ids::text, created_at, summary_sentence
                  FROM event_report WHERE public_event_id = ? ORDER BY version DESC LIMIT 1
                """, (rs, n) -> storedReport(rs), eventId).stream().findFirst();
    }

    private Optional<StoredReport> reportVersion(UUID eventId, int version) {
        return jdbc.query("""
                SELECT id, public_event_id, version, schema_version, report::text, source_ids::text, created_at, summary_sentence
                  FROM event_report WHERE public_event_id = ? AND version = ?
                """, (rs, n) -> storedReport(rs), eventId, version).stream().findFirst();
    }

    private StoredReport storedReport(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> ids = new ArrayList<>();
        read(rs.getString(6)).forEach(id -> ids.add(id.asText()));
        return new StoredReport(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4),
                read(rs.getString(5)), ids, rs.getTimestamp(7).toInstant(), rs.getString(8));
    }

    private Optional<ApprovalView> approvalOf(UUID reportId, String agent) {
        return approvalForReport(reportId, agent).map(this::view);
    }

    private ApprovalView view(StoredApproval a) {
        Instant now = clock.instant();
        boolean valid = a.text() != null && a.validUntil().isAfter(now);
        boolean stale = valid && !a.approvedAt().plus(properties.staleAfter()).isAfter(now);
        return new ApprovalView(a.id(), a.eventId(), a.version(), a.edited(), a.approvedAt(), a.validUntil(), valid, stale);
    }

    private Optional<StoredApproval> approvalForReport(UUID reportId, String agent) {
        return queryApprovals("ar.event_report_id = ? AND ar.agent_sub = ?", reportId, agent).stream().findFirst();
    }

    private Optional<StoredApproval> approvalById(UUID approvalId) {
        return queryApprovals("ar.id = ? AND ar.final_text IS NOT NULL", approvalId).stream().findFirst();
    }

    /** The agent's newest approval on the event that is still valid. */
    private Optional<StoredApproval> validApproval(UUID eventId, String agent) {
        return queryApprovals("ar.public_event_id = ? AND ar.agent_sub = ? AND ar.final_text IS NOT NULL AND ar.valid_until > ?",
                eventId, agent, Timestamp.from(clock.instant())).stream().findFirst();
    }

    private Optional<StoredApproval> latestApproval(UUID eventId, String agent) {
        return queryApprovals("ar.public_event_id = ? AND ar.agent_sub = ?", eventId, agent).stream().findFirst();
    }

    private List<StoredApproval> queryApprovals(String where, Object... args) {
        return jdbc.query("""
                SELECT ar.id, ar.event_report_id, ar.public_event_id, ar.version, ar.agent_sub, ar.final_text::text,
                       ar.edited, ar.final_hash, ar.approved_at, ar.valid_until, r.summary_sentence, r.created_at,
                       (SELECT max(sr.published_at) FROM source_result sr
                         WHERE sr.id::text IN (SELECT jsonb_array_elements_text(r.source_ids))) AS news_date
                  FROM approved_report ar JOIN event_report r ON r.id = ar.event_report_id
                 WHERE %s
                 ORDER BY ar.version DESC
                """.formatted(where), (rs, n) -> {
            Timestamp news = rs.getTimestamp(13);
            String text = rs.getString(6);
            return new StoredApproval(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                    rs.getInt(4), rs.getString(5), text == null ? null : read(text), rs.getBoolean(7), rs.getString(8).strip(),
                    rs.getTimestamp(9).toInstant(), rs.getTimestamp(10).toInstant(),
                    news == null ? rs.getTimestamp(12).toInstant() : news.toInstant(), rs.getString(11));
        }, args);
    }

    private Instant newsDate(StoredReport report) {
        Timestamp news = jdbc.queryForObject("""
                SELECT max(sr.published_at) FROM source_result sr WHERE sr.id::text IN (SELECT jsonb_array_elements_text(CAST(? AS jsonb)))
                """, Timestamp.class, json(report.sourceIds()));
        return news == null ? report.createdAt() : news.toInstant();
    }

    /** The sources the model saw, in the report's order, numbered from 1 in the PDF. */
    private List<ReportPdfRenderer.SourceLine> sourcesOf(UUID reportId) {
        StoredReport report = jdbc.query("""
                SELECT id, public_event_id, version, schema_version, report::text, source_ids::text, created_at, summary_sentence
                  FROM event_report WHERE id = ?
                """, (rs, n) -> storedReport(rs), reportId).getFirst();
        Map<String, ReportPdfRenderer.SourceLine> byId = new LinkedHashMap<>();
        jdbc.query("""
                SELECT sr.id::text, sr.title, sr.source_domain, sr.published_at, sr.canonical_url FROM source_result sr
                 WHERE sr.id::text IN (SELECT jsonb_array_elements_text(CAST(? AS jsonb)))
                """, rs -> {
            Timestamp published = rs.getTimestamp(4);
            byId.put(rs.getString(1), new ReportPdfRenderer.SourceLine(rs.getString(1), rs.getString(2), rs.getString(3),
                    published == null ? null : published.toInstant(), rs.getString(5)));
        }, json(report.sourceIds()));
        List<ReportPdfRenderer.SourceLine> ordered = new ArrayList<>();
        for (String id : report.sourceIds()) {
            ReportPdfRenderer.SourceLine line = byId.get(id);
            if (line != null) ordered.add(line);
        }
        return ordered;
    }

    private Map<String, ReportEdits.SourceText> sourceTexts(StoredReport report) {
        Map<String, ReportEdits.SourceText> texts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT sr.id::text, sr.title, sr.excerpt FROM source_result sr
                 WHERE sr.id::text IN (SELECT jsonb_array_elements_text(CAST(? AS jsonb)))
                """, rs -> {
            texts.put(rs.getString(1), new ReportEdits.SourceText(rs.getString(2), rs.getString(3)));
        }, json(report.sourceIds()));
        return texts;
    }

    private Issuer issuerOf(UUID eventId) {
        return jdbc.query("""
                SELECT ins.issuer_name, ins.ticker FROM public_event pe
                  JOIN instrument ins ON ins.id = COALESCE(pe.instrument_id,
                       (SELECT min(i.instrument_id) FROM public_event_instrument i WHERE i.public_event_id = pe.id))
                 WHERE pe.id = ?
                """, (rs, n) -> new Issuer(rs.getString(1), rs.getString(2)), eventId).stream().findFirst()
                .orElse(new Issuer("Company update", null));
    }

    private String issuerName(String instrumentId, Issuer fallback) {
        return jdbc.queryForList("SELECT issuer_name FROM instrument WHERE id = ?", String.class, instrumentId).stream()
                .findFirst().orElse(fallback.name());
    }

    /**
     * The line the email's summary uses for one report (plan 5.5): the AI summary sentence when the report was not
     * edited and has one, otherwise the first sentence of the approved executive summary. Agent-edited text never
     * goes to a model. Citation markers are removed.
     */
    static String summaryLine(boolean edited, String summarySentence, JsonNode text) {
        String line = !edited && summarySentence != null && !summarySentence.isBlank() ? summarySentence
                : text.path("executiveSummary").path(0).path("text").asText("");
        line = CITATION.matcher(line).replaceAll("").strip();
        return line.length() <= 400 ? line : line.substring(0, 397).strip() + "...";
    }

    private static String summaryLine(StoredApproval approval) {
        return summaryLine(approval.edited(), approval.summarySentence(), approval.text());
    }

    private static PackageItem refusedItem(UUID alertId, String outcome) {
        return new PackageItem(alertId, outcome, null, null, null, null, null, null, null, false, null, null);
    }

    private static List<String> changedSections(JsonNode before, JsonNode after) {
        List<String> changed = new ArrayList<>();
        for (String section : ReportEdits.SECTIONS) {
            if (!before.path(section).equals(after.path(section))) changed.add(section);
        }
        return changed;
    }

    private void ensureAuditCapacity() {
        try {
            if (outbox.countByStatusIn(BACKLOG_STATUSES) >= maxPendingAuditEvents) {
                throw new AuditUnavailableException("audit outbox is at capacity");
            }
        } catch (DataAccessException exception) {
            throw new AuditUnavailableException("audit outbox availability cannot be verified", exception);
        }
    }

    private void audit(String type, Map<String, Object> payload, Instant now) {
        try {
            outbox.saveAndFlush(new AuditOutbox(UUID.randomUUID(), type, 1, mapper.writeValueAsString(payload), now));
        } catch (DataAccessException | JsonProcessingException exception) {
            throw new AuditUnavailableException("durable audit persistence is unavailable", exception);
        }
    }

    private JsonNode read(String text) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored report unreadable", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise", e);
        }
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
