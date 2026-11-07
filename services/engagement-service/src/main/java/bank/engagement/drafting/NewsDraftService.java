package bank.engagement.drafting;

import bank.engagement.audit.AuditAction;
import bank.engagement.audit.AuditTrail;
import bank.engagement.integration.clients.ClientDirectory;
import bank.engagement.integration.clients.ClientRecord;
import bank.engagement.integration.identity.AgentDirectory;
import bank.engagement.integration.identity.AgentProfile;
import bank.engagement.integration.sources.NewsReports;
import bank.engagement.integration.sources.SourceNotFoundException;
import bank.engagement.integration.sources.SourceUnavailableException;
import bank.engagement.notifications.ClientMailSender;
import bank.engagement.notifications.HtmlText;
import bank.engagement.notifications.MailProperties;
import bank.engagement.notifications.MailSendException;
import bank.engagement.notifications.MailUnavailableException;
import bank.engagement.notifications.OutgoingMail;
import bank.engagement.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * News Alert drafts (news-alert-drafts plan, sections 5.5 to 5.7). A draft is one client's email: a short summary
 * built from the approved reports, and one combined PDF rendered by event-alert-service when it is previewed or
 * sent. No model is called here. Every call to event-alert-service uses the agent's own token, and ownership, status
 * and approval are checked again there when a report is added and again just before sending.
 */
@Service
@Slf4j
public class NewsDraftService {

    private static final Set<String> SENT_OUT_OK = Set.of("SENT_OUT", "ALREADY_SENT_OUT");
    /** After a send these never change, so retrying is pointless: the report is listed as not marked. */
    private static final Set<String> SENT_OUT_FINAL = Set.of("NOT_AUTHORISED", "INVALID_STATUS");

    /** What one Notify click did for each client. */
    public record CreatedDraft(UUID draftId, UUID clientId, boolean created, int reportCount, List<UUID> added) {
    }

    /** {@code reason}: NOT_AUTHORISED, NOT_INFORMED, NOT_APPROVED, ALREADY_ON_DRAFT, DRAFT_BEING_SENT or LIMIT_REACHED. */
    public record SkippedReport(UUID alertId, String clientRef, String reason) {
    }

    public record CreateResult(List<CreatedDraft> drafts, List<SkippedReport> skipped) {
    }

    private final EmailDraftRepository repository;
    private final NewsDraftStore store;
    private final NewsReports reports;
    private final ClientDirectory clients;
    private final AgentDirectory agents;
    private final ClientMailSender sender;
    private final MailProperties mail;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int maxReports;
    private final int summaryLines;

    public NewsDraftService(EmailDraftRepository repository, NewsDraftStore store, NewsReports reports,
            ClientDirectory clients, AgentDirectory agents, ClientMailSender sender, MailProperties mail,
            AuditTrail audit, TransactionTemplate transactions, Clock clock,
            @Value("${engagement.news.max-reports-per-email:30}") int maxReports,
            @Value("${engagement.news.summary-lines:10}") int summaryLines) {
        this.repository = repository;
        this.store = store;
        this.reports = reports;
        this.clients = clients;
        this.agents = agents;
        this.sender = sender;
        this.mail = mail;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
        this.maxReports = maxReports;
        this.summaryLines = summaryLines;
    }

    // ---- creating and changing drafts -----------------------------------------------------------------------------

    /**
     * "Notify selected clients of news": each chosen client's approved report goes on that client's open News Alert
     * draft, or a new one. A report already on an open or sent draft is not added again.
     */
    public CreateResult create(String owner, UUID eventId, List<UUID> alertIds) {
        List<NewsReports.PackageItem> items;
        try {
            items = reports.packageFor(eventId, alertIds);
        } catch (SourceNotFoundException ex) {
            throw ApiException.notFound("The event is not one of yours");
        } catch (SourceUnavailableException ex) {
            throw ApiException.unavailable("Event alerts are unavailable; no draft was made");
        }
        List<SkippedReport> skipped = new ArrayList<>();
        Map<UUID, List<NewsReports.PackageItem>> byClient = new LinkedHashMap<>();
        for (NewsReports.PackageItem item : items) {
            if (!item.ok()) {
                skipped.add(new SkippedReport(item.alertId(), item.clientRef(), item.outcome()));
                continue;
            }
            UUID clientId;
            try {
                clientId = UUID.fromString(item.clientRef());
            } catch (IllegalArgumentException notCrm) {
                skipped.add(new SkippedReport(item.alertId(), item.clientRef(), "NOT_AUTHORISED"));
                continue;
            }
            byClient.computeIfAbsent(clientId, id -> new ArrayList<>()).add(item);
        }
        List<CreatedDraft> drafts = new ArrayList<>();
        for (Map.Entry<UUID, List<NewsReports.PackageItem>> entry : byClient.entrySet()) {
            CreatedDraft created = addForClient(owner, eventId, entry.getKey(), entry.getValue(), skipped);
            if (created != null) drafts.add(created);
        }
        return new CreateResult(drafts, skipped);
    }

    /**
     * One client's reports, in one transaction. Two Notify clicks at the same moment can each try to make the client's
     * draft, or add the same report: the database refuses the second (one open draft per client, one active draft per
     * report). A refused statement poisons the Postgres transaction, so the whole client is tried once more in a fresh
     * one, which then finds the other click's draft and adds to it.
     */
    private CreatedDraft addForClient(String owner, UUID eventId, UUID clientId, List<NewsReports.PackageItem> items,
            List<SkippedReport> skipped) {
        for (int attempt = 1; ; attempt++) {
            List<SkippedReport> attemptSkipped = new ArrayList<>();
            try {
                CreatedDraft created = transactions.execute(status -> addToDraft(owner, eventId, clientId, items, attemptSkipped));
                skipped.addAll(attemptSkipped);
                return created;
            } catch (DataIntegrityViolationException race) {
                if (attempt >= 2) throw race;
            }
        }
    }

    private CreatedDraft addToDraft(String owner, UUID eventId, UUID clientId, List<NewsReports.PackageItem> items,
            List<SkippedReport> skipped) {
        Instant now = clock.instant();
        // A draft being sent is not added to: its reports were already read for the send.
        if (repository.findFirstByOwnerAgentSubAndClientIdAndPurposeAndStatusOrderByCreatedAtDesc(
                owner, clientId, DraftPurpose.NEWS_ALERT, DraftStatus.SENDING).isPresent()) {
            items.forEach(item -> skipped.add(new SkippedReport(item.alertId(), item.clientRef(), "DRAFT_BEING_SENT")));
            return null;
        }
        Optional<EmailDraft> open = repository.findFirstByOwnerAgentSubAndClientIdAndPurposeAndStatusOrderByCreatedAtDesc(
                owner, clientId, DraftPurpose.NEWS_ALERT, DraftStatus.DRAFT);
        boolean created = open.isEmpty();
        EmailDraft draft = open.orElseGet(() -> repository.saveAndFlush(
                EmailDraft.news(UUID.randomUUID(), owner, clientId, "Your portfolio update", "Dear {{client_first_name}},", now)));
        List<NewsDraftStore.Report> before = created ? List.of() : store.reports(draft.getId());
        int count = before.size();
        List<UUID> added = new ArrayList<>();
        for (NewsReports.PackageItem item : items) {
            if (store.active(item.alertId())) {
                skipped.add(new SkippedReport(item.alertId(), item.clientRef(), "ALREADY_ON_DRAFT"));
                continue;
            }
            if (count >= maxReports) {
                skipped.add(new SkippedReport(item.alertId(), item.clientRef(), "LIMIT_REACHED"));
                continue;
            }
            // A duplicate here (the same report added by a parallel click) aborts this transaction; addForClient retries.
            store.add(new NewsDraftStore.Report(draft.getId(), item.alertId(), item.approvalId(), eventId,
                    trim(item.issuer(), 200), trim(item.headline(), 300), item.newsDate(), item.validUntil(),
                    item.finalHash(), trim(item.summaryLine(), 400), count), now);
            count++;
            added.add(item.alertId());
        }
        if (added.isEmpty()) {
            if (created) {
                // Nothing could go on it: the empty draft is not kept.
                store.release(draft.getId());
                repository.delete(draft);
            }
            return null;
        }
        refreshText(draft, before, now);
        Map<String, Object> data = DraftService.auditData(draft);
        data.put("purpose", DraftPurpose.NEWS_ALERT.name());
        data.put("tone", DraftTone.NONE.name());
        data.put("source", DraftSource.NEWS.name());
        data.put("alertIds", added.stream().map(UUID::toString).toList());
        data.put("reportCount", count);
        data.put("extended", !created);
        audit.record(AuditAction.DRAFT_GENERATE, owner, data);
        return new CreatedDraft(draft.getId(), clientId, created, count, added);
    }

    /** Takes one report off an open draft; the client stays in Plan client briefing. The last one discards the draft. */
    public void removeReport(String owner, UUID draftId, UUID alertId) {
        transactions.executeWithoutResult(status -> {
            EmailDraft draft = openNews(owner, draftId);
            List<NewsDraftStore.Report> before = store.reports(draftId);
            if (store.remove(draftId, alertId) == 0) {
                throw ApiException.notFound("That report is not on this draft");
            }
            Instant now = clock.instant();
            Map<String, Object> data = DraftService.auditData(draft);
            data.put("alertId", alertId.toString());
            audit.record(AuditAction.DRAFT_REPORT_REMOVED, owner, data);
            if (before.size() == 1) {
                draft.discard(now);
                repository.saveAndFlush(draft);
                store.release(draftId);
                Map<String, Object> discard = DraftService.auditData(draft);
                discard.put("reason", "last_report_removed");
                audit.record(AuditAction.DRAFT_DISCARD, owner, discard);
                return;
            }
            refreshText(draft, before, now);
        });
    }

    /** "Rebuild summary": the summary and subject are built again from the reports; {@code keep} keeps the agent's text. */
    public void rebuild(String owner, UUID draftId, boolean keep) {
        transactions.executeWithoutResult(status -> {
            EmailDraft draft = openNews(owner, draftId);
            if (keep) {
                store.suggestRebuild(draftId, false);
                return;
            }
            List<NewsText.Line> lines = lines(store.reports(draftId));
            String body = NewsText.summary(lines, summaryLines);
            draft.rebuilt(NewsText.subject(lines), body, clock.instant());
            repository.saveAndFlush(draft);
            store.saveNews(draftId, NewsText.hash(body), false);
        });
    }

    /**
     * After the reports changed: the summary and subject are rebuilt if the agent has not changed them; otherwise
     * their text is kept and the page offers "Rebuild summary?".
     */
    private void refreshText(EmailDraft draft, List<NewsDraftStore.Report> before, Instant now) {
        List<NewsText.Line> lines = lines(store.reports(draft.getId()));
        Optional<NewsDraftStore.News> news = store.news(draft.getId());
        boolean summaryUntouched = news.isEmpty() || news.get().builtSummaryHash().equals(NewsText.hash(draft.getBodyTemplate()));
        boolean subjectUntouched = news.isEmpty() || NewsText.subject(lines(before)).equals(draft.getSubjectTemplate());
        String body = summaryUntouched ? NewsText.summary(lines, summaryLines) : draft.getBodyTemplate();
        String subject = subjectUntouched ? NewsText.subject(lines) : draft.getSubjectTemplate();
        draft.rebuilt(subject, body, now);
        repository.saveAndFlush(draft);
        if (summaryUntouched) {
            store.saveNews(draft.getId(), NewsText.hash(body), false);
        } else {
            store.suggestRebuild(draft.getId(), true);
        }
    }

    // ---- reading --------------------------------------------------------------------------------------------------

    /** The news part of a draft's view: its reports, the read-only guide and fixed text, and the summary state. */
    DraftView.NewsView view(EmailDraft draft) {
        if (!draft.isNews()) return null;
        List<NewsDraftStore.Report> rows = store.reports(draft.getId());
        Optional<NewsDraftStore.News> news = store.news(draft.getId());
        List<DraftView.NewsReport> list = rows.stream().map(r -> new DraftView.NewsReport(r.alertId(), r.approvalId(),
                r.eventId(), r.issuer(), r.headline(), r.newsDate(), r.validUntil())).toList();
        boolean edited = draft.hasText() && news.isPresent()
                && !news.get().builtSummaryHash().equals(NewsText.hash(draft.getBodyTemplate()));
        String guide = NewsText.guide("the attached PDF", rows.stream()
                .map(r -> new NewsText.GuideLine(r.issuer(), r.headline(), null)).toList());
        String agentName = agents.find(draft.getOwnerAgentSub()).map(AgentProfile::displayName).orElse("your relationship manager");
        String fixed = NewsText.fixed(NewsText.issuers(lines(rows)), agentName, null);
        Map<UUID, String> issuerByAlert = new HashMap<>();
        rows.forEach(r -> issuerByAlert.put(r.alertId(), r.issuer()));
        List<DraftView.UnmarkedReport> unmarked = news.map(NewsDraftStore.News::unmarked).orElse(List.of()).stream()
                .map(u -> new DraftView.UnmarkedReport(u.alertId(), issuerByAlert.getOrDefault(u.alertId(), "A report"), u.reason()))
                .toList();
        return new DraftView.NewsView(list, edited, news.map(NewsDraftStore.News::rebuildSuggested).orElse(false),
                guide, fixed, news.map(NewsDraftStore.News::sentOutSynced).orElse(false), maxReports, unmarked);
    }

    public List<UUID> openAlerts(String owner) {
        return store.openAlerts(owner);
    }

    /** "Preview PDF": exactly the file the client would get, rendered by event-alert-service. */
    public NewsReports.Pdf preview(String owner, UUID draftId) {
        EmailDraft draft = news(owner, draftId);
        List<NewsDraftStore.Report> rows = store.reports(draftId);
        if (rows.isEmpty()) throw ApiException.conflict("This draft has no reports");
        return pdf(rows, agentName(owner));
    }

    private NewsReports.Pdf pdf(List<NewsDraftStore.Report> rows, String preparedBy) {
        try {
            return reports.combinedPdf(rows.stream().map(NewsDraftStore.Report::approvalId).distinct().toList(), preparedBy);
        } catch (SourceNotFoundException ex) {
            throw ApiException.conflict("An approved report on this draft is no longer available; remove it and try again");
        } catch (SourceUnavailableException ex) {
            throw ApiException.unavailable("The report PDF could not be made right now; nothing was sent. "
                    + "If this keeps happening, remove some reports and try again");
        }
    }

    // ---- sending --------------------------------------------------------------------------------------------------

    /**
     * Sends one News Alert draft (news-alert-drafts plan, section 5.6): every report is checked again, the combined
     * PDF is fetched and checked against the approved hashes, one email goes to the client with the PDF attached, and
     * the clients move to Sent Out. Nothing is sent if any check fails.
     */
    EmailDraft send(String owner, UUID draftId, int version) {
        EmailDraft draft = news(owner, draftId);
        if (draft.getStatus() == DraftStatus.SENT || draft.getStatus() == DraftStatus.DISCARDED) {
            throw ApiException.conflict("This draft has already been " + draft.getStatus().name().toLowerCase());
        }
        if (version != draft.getVersion()) {
            throw ApiException.conflict("The draft was changed by another request; reload and try again");
        }
        List<NewsDraftStore.Report> rows = store.reports(draftId);
        if (rows.isEmpty()) throw ApiException.unprocessable("This draft has no reports");
        ClientRecord client = clients.findOwned(draft.getClientId());
        AgentProfile agent = agents.find(owner)
                .filter(profile -> profile.enabled() && profile.hasEmail())
                .orElseThrow(() -> ApiException.unavailable("Your email address could not be looked up; nothing was sent"));
        Map<String, String> values = new HashMap<>();
        values.put(Placeholders.CLIENT_FIRST_NAME, client.firstName());
        values.put(Placeholders.AGENT_NAME, agent.displayName());
        String subject = Placeholders.fill(draft.getSubjectTemplate(), values);
        String body = Placeholders.fill(draft.getBodyTemplate(), values);
        if (subject.isBlank() || body.isBlank()) throw ApiException.unprocessable("The subject and body must not be empty");
        if (Placeholders.unresolved(subject) || Placeholders.unresolved(body)) {
            throw ApiException.unprocessable("The draft still has a placeholder that cannot be filled in");
        }
        if (client.email() == null || client.email().isBlank()) {
            throw ApiException.unprocessable("The client has no email address");
        }

        // 1. Each report must still be the agent's, in Plan client briefing and covered by the same valid approval.
        List<UUID> alertIds = rows.stream().map(NewsDraftStore.Report::alertId).toList();
        List<NewsReports.CheckItem> checks;
        try {
            checks = reports.sendCheck(alertIds);
        } catch (SourceNotFoundException | SourceUnavailableException ex) {
            throw ApiException.unavailable("Event alerts are unavailable; nothing was sent");
        }
        List<String> problems = new ArrayList<>();
        for (NewsDraftStore.Report row : rows) {
            NewsReports.CheckItem check = checks.stream().filter(c -> row.alertId().equals(c.alertId())).findFirst().orElse(null);
            if (check == null || !"OK".equals(check.outcome())) {
                problems.add(row.issuer() + " (" + reason(check == null ? "NOT_AUTHORISED" : check.outcome()) + ")");
            } else if (!row.approvalId().equals(check.approvalId()) || !row.finalHash().equals(check.finalHash())) {
                problems.add(row.issuer() + " (approved again since it was added; remove it and notify again)");
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.conflict("These reports cannot be sent: " + String.join("; ", problems)
                    + ". Remove them from the draft and send again");
        }

        Instant now = clock.instant();
        Integer claimed = transactions.execute(status ->
                repository.claimForSend(draftId, owner, version, now, now.minus(DraftService.STALE_SEND)));
        if (claimed == null || claimed == 0) {
            throw ApiException.conflict("This draft is already being sent or was changed; reload and try again");
        }

        // 2. The combined PDF, checked against the approvals the draft was made from.
        NewsReports.Pdf pdf;
        try {
            pdf = pdf(rows, agent.displayName());
        } catch (RuntimeException ex) {
            transactions.executeWithoutResult(status -> repository.release(draftId));
            throw ex;
        }
        Map<UUID, NewsReports.Section> sections = new HashMap<>();
        pdf.sections().forEach(section -> sections.put(section.approvalId(), section));
        for (NewsDraftStore.Report row : rows) {
            NewsReports.Section section = sections.get(row.approvalId());
            if (section == null || !row.finalHash().equals(section.finalHash())) {
                transactions.executeWithoutResult(status -> repository.release(draftId));
                log.warn("News draft {} not sent: report hash mismatch", draftId);
                throw ApiException.conflict("The approved reports changed; reopen the draft");
            }
        }

        // 3. One email to the client: the agent's text, the guide to the PDF and the fixed text, with the PDF attached.
        List<NewsText.GuideLine> guide = pdf.sections().stream()
                .map(section -> new NewsText.GuideLine(section.issuer(), section.headline(), section.startPage())).toList();
        String text = body.strip() + "\n\n" + NewsText.guide(pdf.fileName(), guide) + "\n\n"
                + NewsText.fixed(NewsText.issuers(lines(rows)), agent.displayName(), agent.email());
        String messageId;
        try {
            messageId = sender.send(new OutgoingMail(agent.displayName() + " via Scrooge Bank", mail.fromAddress(),
                    client.email(), agent.email(), agent.email(), subject, text, HtmlText.of(text), null,
                    List.of(new OutgoingMail.Attachment(pdf.fileName(), "application/pdf", pdf.bytes()))));
        } catch (MailUnavailableException ex) {
            transactions.executeWithoutResult(status -> repository.release(draftId));
            throw ApiException.unavailable("Email sending is not configured; nothing was sent");
        } catch (MailSendException ex) {
            log.warn("News draft {} not sent: {}", draftId, ex.code());
            transactions.executeWithoutResult(status -> {
                repository.release(draftId);
                Map<String, Object> data = DraftService.auditData(draft);
                data.put("errorCode", ex.code());
                audit.record(AuditAction.EMAIL_SEND_FAILED, owner, data);
            });
            throw ApiException.badGateway("The email service did not accept the message; the draft was not sent");
        }

        EmailDraft sent = transactions.execute(status -> {
            EmailDraft current = repository.findById(draftId).orElseThrow();
            current.sent(messageId, clock.instant());
            EmailDraft saved = repository.saveAndFlush(current);
            store.clearText(draftId);
            Map<String, Object> data = DraftService.auditData(saved);
            data.put("sesMessageId", messageId);
            data.put("purpose", DraftPurpose.NEWS_ALERT.name());
            data.put("source", DraftSource.NEWS.name());
            data.put("edited", saved.isEdited());
            data.put("alertIds", alertIds.stream().map(UUID::toString).toList());
            data.put("approvalIds", rows.stream().map(r -> r.approvalId().toString()).distinct().toList());
            data.put("finalHashes", rows.stream().map(NewsDraftStore.Report::finalHash).toList());
            data.put("pdfHash", pdf.pdfHash());
            data.put("reportCount", rows.size());
            audit.record(AuditAction.EMAIL_SEND, owner, data);
            return saved;
        });

        // 4. The clients move to Sent Out. If this fails the email is still sent; the page offers a retry.
        recordSentOut(draftId, alertIds, messageId);
        return sent;
    }

    /** "Retry status update" after a sent email whose Sent Out call failed. */
    public void retrySentOut(String owner, UUID draftId) {
        EmailDraft draft = news(owner, draftId);
        if (draft.getStatus() != DraftStatus.SENT) throw ApiException.conflict("This draft has not been sent");
        String messageId = draft.getSesMessageId();
        List<UUID> alertIds = store.reports(draftId).stream().map(NewsDraftStore.Report::alertId).toList();
        if (!recordSentOut(draftId, alertIds, messageId == null ? "unknown" : messageId)) {
            throw ApiException.unavailable("Event alerts are unavailable; the status was not updated. Try again later");
        }
    }

    /**
     * Moves the clients to Sent Out. A report the alerts service refuses for good (the client was reassigned, or is not
     * in Plan client briefing any more) is final: it is listed as not marked and no longer keeps the retry button
     * alive. Only a failed call, or an answer we do not know, can still be retried.
     *
     * @return true when nothing is left to retry
     */
    private boolean recordSentOut(UUID draftId, List<UUID> alertIds, String messageId) {
        boolean synced = true;
        List<NewsDraftStore.Unmarked> unmarked = new ArrayList<>();
        try {
            for (NewsReports.SentOutItem item : reports.sentOut(alertIds, draftId, messageId)) {
                if (SENT_OUT_FINAL.contains(item.outcome())) {
                    unmarked.add(new NewsDraftStore.Unmarked(item.alertId(), item.outcome()));
                } else if (!SENT_OUT_OK.contains(item.outcome())) {
                    synced = false;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Sent Out not recorded for draft {}: {}", draftId, ex.getClass().getSimpleName());
            synced = false;
            unmarked = List.of();
        }
        boolean result = synced;
        List<NewsDraftStore.Unmarked> kept = unmarked;
        transactions.executeWithoutResult(status -> store.sentOutResult(draftId, result, kept));
        return synced;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** A discarded draft (by the agent or the expiry job) gives its reports back; the clients keep their status. */
    void released(UUID draftId) {
        store.release(draftId);
    }

    private EmailDraft news(String owner, UUID draftId) {
        EmailDraft draft = repository.findByIdAndOwnerAgentSub(draftId, owner)
                .orElseThrow(() -> ApiException.notFound("Draft not found"));
        if (!draft.isNews()) throw ApiException.badRequest("This is not a News Alert draft");
        return draft;
    }

    private EmailDraft openNews(String owner, UUID draftId) {
        EmailDraft draft = news(owner, draftId);
        if (draft.getStatus() != DraftStatus.DRAFT) {
            throw ApiException.conflict("Only a draft that has not been sent or discarded can be changed");
        }
        return draft;
    }

    private String agentName(String owner) {
        return agents.find(owner).map(AgentProfile::displayName).filter(name -> !name.isBlank()).orElse(null);
    }

    private static List<NewsText.Line> lines(List<NewsDraftStore.Report> rows) {
        return rows.stream().map(r -> new NewsText.Line(r.issuer(), r.headline(), r.summaryLine())).toList();
    }

    private static String trim(String text, int maximum) {
        if (text == null) return null;
        String stripped = text.strip();
        return stripped.length() <= maximum ? stripped : stripped.substring(0, maximum - 3).strip() + "...";
    }

    private static String reason(String outcome) {
        return switch (outcome) {
            case "EXPIRED" -> "its approval expired; approve it again";
            case "NOT_APPROVED" -> "not approved";
            case "NOT_INFORMED" -> "the client is no longer in Plan client briefing";
            default -> "no longer one of your clients";
        };
    }
}
