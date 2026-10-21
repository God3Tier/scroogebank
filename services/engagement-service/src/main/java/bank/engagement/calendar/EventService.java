package bank.engagement.calendar;

import bank.engagement.audit.AuditAction;
import bank.engagement.audit.AuditTrail;
import bank.engagement.integration.clients.ClientDirectory;
import bank.engagement.notifications.DeliverySummary;
import bank.engagement.notifications.MailKind;
import bank.engagement.notifications.MailQueue;
import bank.engagement.notifications.Preferences;
import bank.engagement.notifications.PreferencesService;
import bank.engagement.reminders.ReminderSchedule;
import bank.engagement.web.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An agent's events. Each write saves the event, the mail it causes and its
 * audit event in one transaction (ADR-0051 decision 7). A linked client is
 * checked with client-service before anything is saved, and a client-service
 * failure saves nothing (fail closed).
 */
@Service
public class EventService {

    static final Duration MAX_LIST_WINDOW = Duration.ofDays(92);
    static final Duration MAX_EVENT_LENGTH = Duration.ofHours(24);
    static final int MAX_LISTED = 500;

    private final CalendarEventRepository repository;
    private final ClientDirectory clients;
    private final MailQueue mailQueue;
    private final ReminderSchedule reminders;
    private final PreferencesService preferences;
    private final AuditTrail audit;
    private final Clock clock;

    public EventService(CalendarEventRepository repository, ClientDirectory clients, MailQueue mailQueue,
            ReminderSchedule reminders, PreferencesService preferences, AuditTrail audit, Clock clock) {
        this.repository = repository;
        this.clients = clients;
        this.mailQueue = mailQueue;
        this.reminders = reminders;
        this.preferences = preferences;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<EventView> list(String owner, Instant from, Instant to, boolean includeCancelled) {
        if (!to.isAfter(from)) {
            throw ApiException.badRequest("'to' must be after 'from'");
        }
        if (Duration.between(from, to).compareTo(MAX_LIST_WINDOW) > 0) {
            throw ApiException.badRequest("The window must be at most 92 days");
        }
        return views(repository.findOverlapping(owner, from, to, includeCancelled, PageRequest.of(0, MAX_LISTED)));
    }

    @Transactional(readOnly = true)
    public EventView get(String owner, UUID id) {
        return views(List.of(find(owner, id))).getFirst();
    }

    @Transactional
    public EventView create(String owner, EventInput input) {
        Instant now = clock.instant();
        CalendarEvent.Details details = details(input.clientId(), input.title(), input.description(),
                input.location(), input.startsAt().toInstant(), input.endsAt().toInstant());
        checkTimes(details);
        if (!details.startsAt().isAfter(now)) {
            throw ApiException.badRequest("startsAt must be in the future");
        }
        List<Integer> offsets = ReminderSchedule.validate(input.reminderOffsetsMinutes());
        if (details.clientId() != null) {
            clients.findOwned(details.clientId());
        }

        CalendarEvent event = repository.saveAndFlush(new CalendarEvent(UUID.randomUUID(), owner, details, now));
        Preferences choices = preferences.get(owner);
        mailQueue.queueCalendarMail(event.getId(), owner, event.getRevision(), MailKind.INVITE, event.getIcsSequence(),
                choices.invitesEnabled(), now);
        reminders.reschedule(event.getId(), owner, event.getRevision(), event.getStartsAt(), offsets,
                choices.remindersEnabled(), now);

        Map<String, Object> data = auditData(event);
        data.put("startsAt", event.getStartsAt().toString());
        data.put("endsAt", event.getEndsAt().toString());
        data.put("reminderOffsetsMinutes", offsets);
        audit.record(AuditAction.EVENT_CREATE, owner, data);
        return views(List.of(event)).getFirst();
    }

    @Transactional
    public EventView update(String owner, UUID id, EventUpdate input) {
        Instant now = clock.instant();
        CalendarEvent event = find(owner, id);
        if (event.isCancelled()) {
            throw ApiException.conflict("A cancelled event cannot be updated");
        }
        if (input.version() != event.getVersion()) {
            throw ApiException.conflict("The event was changed by another request; reload and try again");
        }
        CalendarEvent.Details details = details(input.clientId(), input.title(), input.description(),
                input.location(), input.startsAt().toInstant(), input.endsAt().toInstant());
        checkTimes(details);
        boolean timeChanged = !details.startsAt().equals(event.getStartsAt())
                || !details.endsAt().equals(event.getEndsAt());
        if (timeChanged && !details.startsAt().isAfter(now)) {
            throw ApiException.badRequest("startsAt must be in the future");
        }
        List<Integer> offsets = ReminderSchedule.validate(input.reminderOffsetsMinutes());
        if (details.clientId() != null && !details.clientId().equals(event.getClientId())) {
            clients.findOwned(details.clientId());
        }

        Instant startsBefore = event.getStartsAt();
        Instant endsBefore = event.getEndsAt();
        List<String> changed = event.apply(details, now);
        boolean newInvite = CalendarEvent.needsNewInvite(changed);
        if (newInvite) {
            event.nextSequence();
        }
        event.nextRevision();
        repository.saveAndFlush(event);

        Preferences choices = preferences.get(owner);
        if (newInvite) {
            mailQueue.queueCalendarMail(event.getId(), owner, event.getRevision(), MailKind.UPDATE,
                    event.getIcsSequence(), choices.invitesEnabled(), now);
        }
        reminders.reschedule(event.getId(), owner, event.getRevision(), event.getStartsAt(), offsets,
                choices.remindersEnabled(), now);

        Map<String, Object> data = auditData(event);
        data.put("changedFields", changed);
        if (timeChanged) {
            data.put("startsAtBefore", startsBefore.toString());
            data.put("startsAtAfter", event.getStartsAt().toString());
            data.put("endsAtBefore", endsBefore.toString());
            data.put("endsAtAfter", event.getEndsAt().toString());
        }
        data.put("reminderOffsetsMinutes", offsets);
        audit.record(AuditAction.EVENT_UPDATE, owner, data);
        return views(List.of(event)).getFirst();
    }

    /** Cancelling a cancelled event changes nothing. */
    @Transactional
    public void cancel(String owner, UUID id) {
        Instant now = clock.instant();
        CalendarEvent event = find(owner, id);
        if (event.isCancelled()) {
            return;
        }
        event.cancel(now);
        repository.saveAndFlush(event);
        mailQueue.queueCalendarMail(event.getId(), owner, event.getRevision(), MailKind.CANCEL, event.getIcsSequence(),
                preferences.get(owner).invitesEnabled(), now);
        reminders.cancel(event.getId(), now);
        audit.record(AuditAction.EVENT_CANCEL, owner, auditData(event));
    }

    /** One of the agent's events, without what the agent typed. */
    @Transactional(readOnly = true)
    public Optional<EventSummary> findOwned(String owner, UUID id) {
        return repository.findByIdAndOwnerAgentSub(id, owner)
                .map(event -> new EventSummary(event.getId(), event.getClientId(), event.getStartsAt(),
                        event.getEndsAt(), event.getStatus()));
    }

    /** When the agent's last meeting with this client started, before {@code before}. */
    @Transactional(readOnly = true)
    public Optional<Instant> lastMeetingStart(String owner, UUID clientId, Instant before) {
        return repository.findLastStart(owner, clientId, before);
    }

    private CalendarEvent find(String owner, UUID id) {
        return repository.findByIdAndOwnerAgentSub(id, owner)
                .orElseThrow(() -> ApiException.notFound("Event not found"));
    }

    private static CalendarEvent.Details details(UUID clientId, String title, String description, String location,
            Instant startsAt, Instant endsAt) {
        return new CalendarEvent.Details(clientId, title.strip(), blankToNull(description), blankToNull(location),
                startsAt, endsAt);
    }

    private static void checkTimes(CalendarEvent.Details details) {
        if (!details.endsAt().isAfter(details.startsAt())) {
            throw ApiException.badRequest("endsAt must be after startsAt");
        }
        if (Duration.between(details.startsAt(), details.endsAt()).compareTo(MAX_EVENT_LENGTH) > 0) {
            throw ApiException.badRequest("An event can last at most 24 hours");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Map<String, Object> auditData(CalendarEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("calendarEventId", event.getId().toString());
        data.put("clientId", event.getClientId() == null ? null : event.getClientId().toString());
        data.put("status", event.getStatus().name());
        data.put("sequence", event.getIcsSequence());
        return data;
    }

    private List<EventView> views(List<CalendarEvent> events) {
        Map<UUID, Integer> revisions = events.stream()
                .collect(Collectors.toMap(CalendarEvent::getId, CalendarEvent::getRevision));
        Map<UUID, DeliverySummary> summaries = mailQueue.summaries(revisions);
        Function<CalendarEvent, EventView> toView = event -> {
            DeliverySummary summary = Objects.requireNonNull(summaries.get(event.getId()));
            return new EventView(event.getId(), event.getTitle(), event.getDescription(), event.getLocation(),
                    event.getStartsAt(), event.getEndsAt(), event.getClientId(), event.getStatus(), summary.invite(),
                    summary.reminders(), event.getVersion(), event.getCreatedAt(), event.getUpdatedAt());
        };
        return events.stream().map(toView).toList();
    }
}
