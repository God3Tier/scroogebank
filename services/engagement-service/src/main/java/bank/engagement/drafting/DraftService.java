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
import bank.engagement.notifications.ClientMailSender;
import bank.engagement.notifications.HtmlText;
import bank.engagement.notifications.MailProperties;
import bank.engagement.notifications.MailSendException;
import bank.engagement.notifications.MailUnavailableException;
import bank.engagement.notifications.OutgoingMail;
import bank.engagement.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Follow-up drafts (ADR-0054). Generation and sending call other systems, so
 * they hold no database transaction while waiting; the writes around them are
 * short transactions of their own.
 */
@Service
public class DraftService {

    private static final Logger log = LoggerFactory.getLogger(DraftService.class);

    static final int HOLIDAY_WINDOW_DAYS = 30;
    static final int MAX_HOLIDAYS = 3;
    static final Duration STALE_SEND = Duration.ofMinutes(2);

    private final EmailDraftRepository repository;
    private final DraftGenerator generator;
    private final ClientDirectory clients;
    private final AgentDirectory agents;
    private final EventService events;
    private final HolidayService holidays;
    private final ClientMailSender sender;
    private final MailProperties mail;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DraftService(EmailDraftRepository repository, DraftGenerator generator, ClientDirectory clients,
            AgentDirectory agents, EventService events, HolidayService holidays, ClientMailSender sender,
            MailProperties mail, AuditTrail audit, TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.generator = generator;
        this.clients = clients;
        this.agents = agents;
        this.events = events;
        this.holidays = holidays;
        this.sender = sender;
        this.mail = mail;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    public DraftView create(String owner, DraftInput input) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, TimeConfiguration.SINGAPORE);
        checkEvent(owner, input, now);
        ClientRecord client = clients.findOwned(input.clientId());

        List<HolidayView> upcoming = CountryCodes.toIso(client.country())
                .map(code -> distinctNames(holidays.upcoming(code, today, HOLIDAY_WINDOW_DAYS, MAX_HOLIDAYS * 3)))
                .orElse(List.of());
        if (input.purpose().needsHoliday() && upcoming.isEmpty()) {
            throw ApiException.unprocessable(
                    "The client's country has no public holiday in the next " + HOLIDAY_WINDOW_DAYS + " days");
        }
        MeetingRecency recency = MeetingRecency.of(events.lastMeetingStart(owner, input.clientId(), now), now);

        GeneratedDraft generated = generator.generate(owner, input.purpose(), input.tone(), upcoming, recency, today);

        EmailDraft draft = transactions.execute(status -> {
            EmailDraft saved = repository.saveAndFlush(new EmailDraft(UUID.randomUUID(), owner, input.clientId(),
                    input.eventId(), input.purpose(), input.tone(), generated, clock.instant()));
            Map<String, Object> data = auditData(saved);
            data.put("eventId", saved.getEventId() == null ? null : saved.getEventId().toString());
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
        return view(draft, null);
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

    /** The preview needs client-service; if it cannot answer, the draft is shown without one. */
    public DraftView get(String owner, UUID id) {
        EmailDraft draft = find(owner, id);
        DraftView.Preview preview = null;
        if (draft.getStatus().visible() == DraftStatus.DRAFT) {
            try {
                ClientRecord client = clients.findOwned(draft.getClientId());
                Optional<AgentProfile> agent = agents.find(owner);
                preview = preview(draft, client, agent.map(AgentProfile::displayName).orElse(null));
            } catch (ClientNotFoundException | ClientServiceUnavailableException ex) {
                preview = null;
            }
        }
        return view(draft, preview);
    }

    @Transactional
    public DraftView update(String owner, UUID id, DraftUpdate input) {
        EmailDraft draft = find(owner, id);
        if (draft.getStatus() != DraftStatus.DRAFT) {
            throw ApiException.conflict("Only a draft that has not been sent or discarded can be edited");
        }
        if (input.version() != draft.getVersion()) {
            throw ApiException.conflict("The draft was changed by another request; reload and try again");
        }
        draft.edit(input.subjectTemplate().strip(), input.bodyTemplate().strip(), clock.instant());
        return view(repository.saveAndFlush(draft), null);
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
        audit.record(AuditAction.DRAFT_DISCARD, owner, auditData(draft));
    }

    /**
     * Sends a draft to its client, at most once (ADR-0054 decision 6). The
     * client's address and name come from client-service with the agent's
     * token and are used for this message only (ADR-0053 decision 5).
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
        DraftView.Preview filled = preview(draft, client, agent.displayName());
        if (filled.subject().isBlank() || filled.body().isBlank()) {
            throw ApiException.unprocessable("The subject and body must not be empty");
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
                    client.email(), agent.email(), filled.subject(), filled.body(), HtmlText.of(filled.body()), null));
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
            audit.record(AuditAction.EMAIL_SEND, owner, data);
            return saved;
        });
        return view(sent, filled);
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

    private static DraftView.Preview preview(EmailDraft draft, ClientRecord client, String agentName) {
        Map<String, String> values = new HashMap<>();
        values.put(Placeholders.CLIENT_FIRST_NAME, client.firstName());
        values.put(Placeholders.AGENT_NAME, agentName);
        return new DraftView.Preview(Placeholders.fill(draft.getSubjectTemplate(), values),
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

    private static Map<String, Object> auditData(EmailDraft draft) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("draftId", draft.getId().toString());
        data.put("clientId", draft.getClientId().toString());
        return data;
    }

    private DraftView view(EmailDraft draft, DraftView.Preview preview) {
        HolidayView holiday = holidays.find(draft.getHolidayId()).orElse(null);
        return new DraftView(draft.getId(), draft.getClientId(), draft.getEventId(), draft.getPurpose(),
                draft.getTone(), draft.getSubjectTemplate(), draft.getBodyTemplate(), preview, draft.getSource(),
                draft.getSourceReason(), holiday, draft.getStatus().visible(), draft.getSentAt(), draft.getVersion(),
                draft.getCreatedAt(), draft.getUpdatedAt());
    }
}
