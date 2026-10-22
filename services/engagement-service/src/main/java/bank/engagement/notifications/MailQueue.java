package bank.engagement.notifications;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Queues mail to agents. Callers run inside their own transaction, so an
 * event write and the mail it causes are saved together (ADR-0051 decision 7).
 * Nothing here sends: the dispatcher does.
 */
@Service
@RequiredArgsConstructor
public class MailQueue {

    private final MailQueueRepository repository;

    /**
     * Queues an invite, update or cancellation, replacing any that has not
     * been sent yet. With invites turned off the row is {@code DISABLED}, so
     * the event still shows what happened (ADR-0052 decision 6).
     */
    @Transactional
    public void queueCalendarMail(UUID eventId, String owner, int eventVersion, MailKind kind, int sequence,
            boolean invitesEnabled, Instant now) {
        if (!kind.isCalendar()) {
            throw new IllegalArgumentException("not a calendar mail kind: " + kind);
        }
        repository.cancelPending(eventId, MailKind.CALENDAR, now);
        repository.save(new MailQueueEntry(kind, eventId, owner, eventVersion, 0, sequence, now,
                invitesEnabled ? MailStatus.PENDING : MailStatus.DISABLED, now));
    }

    /**
     * Replaces the event's pending reminders. A reminder whose time has
     * already passed is recorded as {@code MISSED} rather than sent late.
     */
    @Transactional
    public void replaceReminders(UUID eventId, String owner, int eventVersion, List<ReminderSlot> slots,
            boolean remindersEnabled, Instant now) {
        repository.cancelPending(eventId, Set.of(MailKind.REMINDER), now);
        for (ReminderSlot slot : slots) {
            MailStatus status = !remindersEnabled ? MailStatus.DISABLED
                    : slot.dueAt().isAfter(now) ? MailStatus.PENDING
                    : MailStatus.MISSED;
            repository.save(new MailQueueEntry(MailKind.REMINDER, eventId, owner, eventVersion,
                    slot.offsetMinutes(), 0, slot.dueAt(), status, now));
        }
    }

    @Transactional
    public void cancelPendingReminders(UUID eventId, Instant now) {
        repository.cancelPending(eventId, Set.of(MailKind.REMINDER), now);
    }

    /** Turning reminders off cancels every pending one (contract: {@code PUT /preferences}). */
    @Transactional
    public int cancelPendingRemindersFor(String owner, Instant now) {
        return repository.cancelPendingReminders(owner, now);
    }

    /**
     * @param currentRevisions each event's current revision; reminders queued
     *                         for an older revision have been replaced
     */
    @Transactional(readOnly = true)
    public Map<UUID, DeliverySummary> summaries(Map<UUID, Integer> currentRevisions) {
        if (currentRevisions.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<MailQueueEntry>> byEvent = repository.findByEventIdIn(currentRevisions.keySet()).stream()
                .collect(Collectors.groupingBy(MailQueueEntry::getEventId));
        Map<UUID, DeliverySummary> summaries = new HashMap<>();
        currentRevisions.forEach((eventId, revision) ->
                summaries.put(eventId, summary(byEvent.getOrDefault(eventId, List.of()), revision)));
        return summaries;
    }

    private static DeliverySummary summary(Collection<MailQueueEntry> rows, int currentRevision) {
        DeliverySummary.Invite invite = rows.stream()
                .filter(row -> row.getKind().isCalendar())
                .max(Comparator.comparingInt(MailQueueEntry::getIcsSequence)
                        .thenComparing(MailQueueEntry::getCreatedAt))
                .map(row -> new DeliverySummary.Invite(row.getStatus(), row.getIcsSequence()))
                .orElse(new DeliverySummary.Invite(MailStatus.DISABLED, 0));
        List<DeliverySummary.Reminder> reminders = rows.stream()
                .filter(row -> row.getKind() == MailKind.REMINDER && row.getEventVersion() == currentRevision)
                .sorted(Comparator.comparing(MailQueueEntry::getDueAt))
                .map(row -> new DeliverySummary.Reminder(row.getOffsetMinutes(), row.getDueAt(), row.getStatus()))
                .toList();
        return new DeliverySummary(invite, reminders);
    }
}
