package bank.engagement.drafting;

import bank.engagement.audit.AuditAction;
import bank.engagement.audit.AuditTrail;
import bank.engagement.calendar.EventService;
import bank.engagement.calendar.EventSummary;
import bank.engagement.config.TimeConfiguration;
import bank.engagement.holidays.CountryCodes;
import bank.engagement.holidays.HolidayService;
import bank.engagement.holidays.HolidayView;
import bank.engagement.integration.clients.ClientDirectory;
import bank.engagement.integration.clients.ClientNotFoundException;
import bank.engagement.integration.clients.ClientRecord;
import bank.engagement.integration.clients.ClientServiceUnavailableException;
import bank.engagement.integration.identity.AgentDirectory;
import bank.engagement.integration.identity.AgentProfile;
import bank.engagement.integration.sources.FollowUpSources;
import bank.engagement.integration.sources.SourceNotFoundException;
import bank.engagement.integration.sources.SourceUnavailableException;
import bank.engagement.notifications.ClientMailSender;
import bank.engagement.notifications.HtmlText;
import bank.engagement.notifications.MailProperties;
import bank.engagement.notifications.MailSendException;
import bank.engagement.notifications.MailUnavailableException;
import bank.engagement.notifications.OutgoingMail;
import bank.engagement.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Follow-up drafts (ADR-0054, ADR-0068). Generation and sending call other
 * systems, so they hold no database transaction while waiting; the writes
 * around them are short transactions of their own.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DraftService {

    static final int HOLIDAY_WINDOW_DAYS = 30;
    static final int MAX_HOLIDAYS = 3;
    static final Duration STALE_SEND = Duration.ofMinutes(2);
    /** The stored templates' column sizes. */
    static final int MAX_SUBJECT = 120;
    static final int MAX_BODY = 5000;

    private final EmailDraftRepository repository;
    private final DraftGenerator generator;
    private final ClientDirectory clients;
    private final AgentDirectory agents;
    private final FollowUpSources sources;
    private final EventService events;
    private final HolidayService holidays;
    private final ClientMailSender sender;
    private final MailProperties mail;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DraftView create(String owner, DraftInput input) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, TimeConfiguration.SINGAPORE);
        checkEvent(owner, input, now);
        ClientRecord client = clients.findOwned(input.clientId());
        checkSource(input);

        Optional<String> countryCode = CountryCodes.toIso(client.country());
        List<HolidayView> upcoming = countryCode
                .map(code -> distinctNames(holidays.upcoming(code, today, HOLIDAY_WINDOW_DAYS, MAX_HOLIDAYS * 3)))
                .orElse(List.of());
        if (input.purpose().needsHoliday() && countryCode.isEmpty()) {
            throw ApiException.unprocessable("Public holidays are only available for clients in Singapore. "
                    + "Set the client's country to Singapore or SG, or choose another purpose");
        }
        if (input.purpose().needsHoliday() && upcoming.isEmpty()) {
            throw ApiException.unprocessable(
                    "The client's country has no public holiday in the next " + HOLIDAY_WINDOW_DAYS + " days");
        }
        MeetingRecency recency = MeetingRecency.of(events.lastMeetingStart(owner, input.clientId(), now), now);

        GeneratedDraft generated = generator.generate(owner, input.purpose(), input.tone(), upcoming, recency, today);

        EmailDraft draft = transactions.execute(status -> {
            EmailDraft saved = repository.saveAndFlush(
                    new EmailDraft(UUID.randomUUID(), owner, input, generated, clock.instant()));
            Map<String, Object> data = auditData(saved);
            data.put("eventId", saved.getEventId() == null ? null : saved.getEventId().toString());
            putSource(data, saved);
            data.put("purpose", saved.getPurpose().name());
            data.put("tone", saved.getTone().name());
            data.put("source", saved.getSource().name());
            data.put("sourceReason", saved.getSourceReason());
            data.put("promptVersion", saved.getPromptVersion());
            data.put("modelId", saved.getModelId());
            data.put("holidayId", saved.getHolidayId() == null ? null : saved.getHolidayId().toString());
            audit.record(AuditAction.DRAFT_GENERATE, owner, data);
            return saved;
        });
        return view(draft, names(owner, client).map(values -> filled(draft, values)).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<DraftView> list(String owner, DraftStatus status, UUID clientId, int limit) {
        if (limit < 1 || limit > 100) {
            throw ApiException.badRequest("limit must be between 1 and 100");
        }
        Set<DraftStatus> statuses = status == null ? Set.of(DraftStatus.values())
                : status == DraftStatus.DRAFT ? Set.of(DraftStatus.DRAFT, DraftStatus.SENDING)
                : Set.of(status);
        PageRequest page = PageRequest.of(0, limit);
        List<EmailDraft> drafts = clientId == null
                ? repository.findByOwnerAgentSubAndStatusInOrderByCreatedAtDesc(owner, statuses, page)
                : repository.findByOwnerAgentSubAndClientIdAndStatusInOrderByCreatedAtDesc(owner, clientId,
                        statuses, page);
        return drafts.stream().map(draft -> view(draft, null)).toList();
    }

    /**
     * The names need client-service and Cognito; if either cannot answer, the
     * draft is shown without its filled-in text and cannot be edited. A sent
     * or discarded draft has no text left to fill.
     */
    public DraftView get(String owner, UUID id) {
        EmailDraft draft = find(owner, id);
        if (!draft.hasText()) {
            return view(draft, null);
        }
        DraftView.Filled filled;
        try {
            ClientRecord client = clients.findOwned(draft.getClientId());
            filled = names(owner, client).map(values -> filled(draft, values)).orElse(null);
        } catch (ClientNotFoundException | ClientServiceUnavailableException ex) {
            filled = null;
        }
        return view(draft, filled);
    }

    /**
     * Saves the agent's plain text with the client's first name and the
     * agent's name marked as placeholders again, so the stored draft holds no
     * names (ADR-0056). An untouched details slot is marked back the same way.
     * Without the names nothing is saved. The lookups run before the write's
     * transaction, which checks the draft again.
     */
    public DraftView update(String owner, UUID id, DraftUpdate input) {
        EmailDraft draft = find(owner, id);
        checkEditable(draft, input.version());
        ClientRecord client = clients.findOwned(draft.getClientId());
        Map<String, String> values = names(owner, client)
                .orElseThrow(() -> ApiException.unavailable("Your name could not be looked up; the draft was not saved"));
        String subject = Placeholders.mark(input.subject().strip(), values);
        String body = Placeholders.mark(input.body().strip(), values);
        if (subject.length() > MAX_SUBJECT || body.length() > MAX_BODY) {
            throw ApiException.unprocessable("The subject or body is too long");
        }

        EmailDraft saved = transactions.execute(status -> {
            EmailDraft current = find(owner, id);
            checkEditable(current, input.version());
            current.edit(subject, body, clock.instant());
            return repository.saveAndFlush(current);
        });
        return view(saved, filled(saved, values));
    }

    private static void checkEditable(EmailDraft draft, int version) {
        if (draft.getStatus() != DraftStatus.DRAFT) {
            throw ApiException.conflict("Only a draft that has not been sent or discarded can be edited");
        }
        if (version != draft.getVersion()) {
            throw ApiException.conflict("The draft was changed by another request; reload and try again");
        }
    }

    /** Discarding a discarded draft changes nothing. */
    @Transactional
    public void discard(String owner, UUID id) {
        EmailDraft draft = find(owner, id);
        if (draft.getStatus() == DraftStatus.DISCARDED) {
            return;
        }
        if (draft.getStatus() != DraftStatus.DRAFT) {
            throw ApiException.conflict("A sent draft cannot be discarded");
        }
        draft.discard(clock.instant());
        repository.saveAndFlush(draft);
        Map<String, Object> data = auditData(draft);
        data.put("reason", "agent");
        audit.record(AuditAction.DRAFT_DISCARD, owner, data);
    }

    /**
     * Sends a draft to its client, at most once (ADR-0054 decision 6). The
     * client's address and name come from client-service with the agent's
     * token and are used for this message only (ADR-0053 decision 5). The
     * agent gets a Bcc copy, and the draft's text is cleared once SES accepts
     * it (ADR-0068 decision 4). What the agent added is sent as written: it is
     * theirs to check, not the safety screen's (ADR-0068 decision 3).
     */
    public DraftView send(String owner, UUID id, int version) {
        EmailDraft draft = find(owner, id);
        if (draft.getStatus() == DraftStatus.SENT || draft.getStatus() == DraftStatus.DISCARDED) {
            throw ApiException.conflict("This draft has already been " + draft.getStatus().name().toLowerCase());
        }
        if (version != draft.getVersion()) {
            throw ApiException.conflict("The draft was changed by another request; reload and try again");
        }
        ClientRecord client = clients.findOwned(draft.getClientId());
        AgentProfile agent = agents.find(owner)
                .filter(profile -> profile.enabled() && profile.hasEmail())
                .orElseThrow(() -> ApiException.unavailable("Your email address could not be looked up; nothing was sent"));
        DraftView.Filled filled = filled(draft, values(client, agent.displayName()));
        if (filled.subject().isBlank() || filled.body().isBlank()) {
            throw ApiException.unprocessable("The subject and body must not be empty");
        }
        if (filled.subject().contains(Placeholders.AGENT_DETAILS_TOKEN)
                || filled.body().contains(Placeholders.AGENT_DETAILS_TOKEN)) {
            throw ApiException.unprocessable(
                    "Replace " + Placeholders.DETAILS_PROMPT + " with your details, or delete it");
        }
        if (Placeholders.unresolved(filled.subject()) || Placeholders.unresolved(filled.body())) {
            throw ApiException.unprocessable("The draft still has a placeholder that cannot be filled in");
        }
        if (client.email() == null || client.email().isBlank()) {
            throw ApiException.unprocessable("The client has no email address");
        }

        Instant now = clock.instant();
        Integer claimed = transactions.execute(status ->
                repository.claimForSend(id, owner, version, now, now.minus(STALE_SEND)));
        if (claimed == null || claimed == 0) {
            throw ApiException.conflict("This draft is already being sent or was changed; reload and try again");
        }

        String messageId;
        try {
            messageId = sender.send(new OutgoingMail(agent.displayName() + " via Scrooge Bank", mail.fromAddress(),
                    client.email(), agent.email(), agent.email(), filled.subject(), filled.body(),
                    HtmlText.of(filled.body()), null));
        } catch (MailUnavailableException ex) {
            transactions.executeWithoutResult(status -> repository.release(id));
            throw ApiException.unavailable("Email sending is not configured; nothing was sent");
        } catch (MailSendException ex) {
            log.warn("Draft {} not sent: {}", id, ex.code());
            transactions.executeWithoutResult(status -> {
                repository.release(id);
                Map<String, Object> data = auditData(draft);
                data.put("errorCode", ex.code());
                audit.record(AuditAction.EMAIL_SEND_FAILED, owner, data);
            });
            throw ApiException.badGateway("The email service did not accept the message; the draft was not sent");
        }

        EmailDraft sent = transactions.execute(status -> {
            EmailDraft current = repository.findById(id).orElseThrow();
            current.sent(messageId, clock.instant());
            EmailDraft saved = repository.saveAndFlush(current);
            Map<String, Object> data = auditData(saved);
            data.put("sesMessageId", messageId);
            data.put("purpose", saved.getPurpose().name());
            data.put("source", saved.getSource().name());
            data.put("edited", saved.isEdited());
            putSource(data, saved);
            audit.record(AuditAction.EMAIL_SEND, owner, data);
            return saved;
        });
        return view(sent, null);
    }

    /**
     * A follow-up from another feature names the task or alert it came from,
     * which must be the agent's and about this client (ADR-0068 decision 1).
     * Only the source's client is read; nothing from it reaches the model.
     */
    private void checkSource(DraftInput input) {
        DraftPurpose purpose = input.purpose();
        boolean hasTask = input.healthTaskId() != null && !input.healthTaskId().isBlank();
        if (input.healthTaskId() != null && purpose != DraftPurpose.HEALTH_CHECK_IN) {
            throw ApiException.badRequest("healthTaskId is only for HEALTH_CHECK_IN");
        }
        if (input.alertId() != null && purpose != DraftPurpose.ALERT_FOLLOW_UP) {
            throw ApiException.badRequest("alertId is only for ALERT_FOLLOW_UP");
        }
        if (purpose == DraftPurpose.HEALTH_CHECK_IN) {
            if (!hasTask) {
                throw ApiException.badRequest("healthTaskId is required for HEALTH_CHECK_IN");
            }
            checkSourceClient(() -> sources.healthTaskClient(input.healthTaskId()), input.clientId(),
                    "healthTaskId must be one of your Client Health tasks", "The Client Health task is not for this client",
                    "Client Health is unavailable; nothing was saved");
        }
        if (purpose == DraftPurpose.ALERT_FOLLOW_UP) {
            if (input.alertId() == null) {
                throw ApiException.badRequest("alertId is required for ALERT_FOLLOW_UP");
            }
            checkSourceClient(() -> sources.alertClient(input.alertId()), input.clientId(),
                    "alertId must be one of your alerts", "The alert is not for this client",
                    "Alerts are unavailable; nothing was saved");
        }
    }

    /**
     * An alert whose {@code clientRef} is not a CRM client ID (an older,
     * synthetic one) is never this client, so it is refused like any other.
     */
    private static void checkSourceClient(Supplier<String> sourceClient, UUID clientId, String notYours,
            String otherClient, String unavailable) {
        String found;
        try {
            found = sourceClient.get();
        } catch (SourceNotFoundException ex) {
            throw ApiException.badRequest(notYours);
        } catch (SourceUnavailableException ex) {
            throw ApiException.unavailable(unavailable);
        }
        if (!clientId.toString().equalsIgnoreCase(found.strip())) {
            throw ApiException.badRequest(otherClient);
        }
    }

    private static void putSource(Map<String, Object> data, EmailDraft draft) {
        data.put("healthTaskId", draft.getHealthTaskId());
        data.put("alertId", draft.getAlertId() == null ? null : draft.getAlertId().toString());
    }

    private void checkEvent(String owner, DraftInput input, Instant now) {
        if (input.purpose() == DraftPurpose.MEETING_FOLLOW_UP && input.eventId() == null) {
            throw ApiException.badRequest("eventId is required for MEETING_FOLLOW_UP");
        }
        if (input.eventId() == null) {
            return;
        }
        EventSummary event = events.findOwned(owner, input.eventId())
                .orElseThrow(() -> ApiException.badRequest("eventId must be one of your events"));
        if (!input.clientId().equals(event.clientId())) {
            throw ApiException.badRequest("The event is not with this client");
        }
        if (input.purpose() == DraftPurpose.MEETING_FOLLOW_UP && event.endsAt().isAfter(now)) {
            throw ApiException.badRequest("The meeting has not ended yet");
        }
    }

    private EmailDraft find(String owner, UUID id) {
        return repository.findByIdAndOwnerAgentSub(id, owner)
                .orElseThrow(() -> ApiException.notFound("Draft not found"));
    }

    /**
     * What the agent sees and saves: both names and the details slot's prompt,
     * or empty when the agent or either name cannot be found. Sending uses
     * {@link #values} instead, which leaves the slot unfilled.
     */
    private Optional<Map<String, String>> names(String owner, ClientRecord client) {
        if (client.firstName() == null || client.firstName().isBlank()) {
            return Optional.empty();
        }
        return agents.find(owner)
                .map(AgentProfile::displayName)
                .filter(name -> !name.isBlank())
                .map(name -> {
                    Map<String, String> shown = values(client, name);
                    shown.put(Placeholders.AGENT_DETAILS, Placeholders.DETAILS_PROMPT);
                    return shown;
                });
    }

    private static Map<String, String> values(ClientRecord client, String agentName) {
        Map<String, String> values = new HashMap<>();
        values.put(Placeholders.CLIENT_FIRST_NAME, client.firstName());
        values.put(Placeholders.AGENT_NAME, agentName);
        return values;
    }

    private static DraftView.Filled filled(EmailDraft draft, Map<String, String> values) {
        return new DraftView.Filled(Placeholders.fill(draft.getSubjectTemplate(), values),
                Placeholders.fill(draft.getBodyTemplate(), values));
    }

    /** One holiday per name (an observed Monday repeats its holiday's name), nearest first. */
    private static List<HolidayView> distinctNames(List<HolidayView> found) {
        Set<String> names = new HashSet<>();
        List<HolidayView> distinct = new ArrayList<>();
        for (HolidayView holiday : found) {
            if (names.add(holiday.name()) && distinct.size() < MAX_HOLIDAYS) {
                distinct.add(holiday);
            }
        }
        return distinct;
    }

    static Map<String, Object> auditData(EmailDraft draft) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("draftId", draft.getId().toString());
        data.put("clientId", draft.getClientId().toString());
        return data;
    }

    private DraftView view(EmailDraft draft, DraftView.Filled filled) {
        HolidayView holiday = holidays.find(draft.getHolidayId()).orElse(null);
        return new DraftView(draft.getId(), draft.getClientId(), draft.getEventId(), draft.getHealthTaskId(),
                draft.getAlertId(), draft.getPurpose(), draft.getTone(),
                filled == null ? null : filled.subject(), filled == null ? null : filled.body(),
                draft.getSubjectTemplate(), draft.getBodyTemplate(), draft.isEdited(), draft.getSource(),
                draft.getSourceReason(), holiday, draft.getStatus().visible(), draft.getSentAt(), draft.getVersion(),
                draft.getCreatedAt(), draft.getUpdatedAt());
    }
}
