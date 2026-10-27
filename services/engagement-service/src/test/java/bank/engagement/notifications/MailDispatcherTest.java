package bank.engagement.notifications;

import bank.engagement.calendar.EventInput;
import bank.engagement.calendar.EventService;
import bank.engagement.calendar.EventView;
import bank.engagement.testsupport.PostgresIntegrationTest;
import bank.engagement.testsupport.TestBeans;
import bank.engagement.testsupport.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** The dispatcher against PostgreSQL (ADR-0051 decision 4). */
class MailDispatcherTest extends PostgresIntegrationTest {

    @Autowired
    private EventService events;

    @Autowired
    private MailDispatcher dispatcher;

    @Autowired
    private MailDispatchService rows;

    private EventView createEvent(String title, Duration fromNow, List<Integer> offsets) {
        OffsetDateTime start = TestBeans.NOW.plus(fromNow).atOffset(ZoneOffset.ofHours(8));
        return events.create(Tokens.AGENT, new EventInput(title, null, "Level 3", start, start.plusHours(1), null,
                offsets));
    }

    private List<String> statuses(UUID eventId, String kind) {
        return jdbc.queryForList("SELECT status FROM mail_queue WHERE event_id = ? AND kind = ? ORDER BY created_at",
                String.class, eventId, kind);
    }

    @Test
    void sendsTheInviteOnceToTheAgentAndRecordsIt() {
        EventView event = createEvent("Portfolio review", Duration.ofDays(1), List.of(60));

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(dispatcher.dispatchDue()).isZero();

        assertThat(channel.sent()).hasSize(1);
        OutgoingMail mail = channel.sent().getFirst();
        assertThat(mail.to()).isEqualTo("synthetic.agent@example.test");
        assertThat(mail.fromName()).isEqualTo("CRM Calendar");
        assertThat(mail.fromAddress()).isEqualTo("no-reply@mail.example.test");
        assertThat(mail.subject()).startsWith("Invitation: Portfolio review @ Thu 22 Oct 2026, 10:00 AM");
        assertThat(mail.calendar().method()).isEqualTo("REQUEST");
        assertThat(mail.calendar().icsText()).contains("UID:" + event.id() + "@mail.example.test");
        assertThat(statuses(event.id(), "INVITE")).containsExactly("SENT");
        assertThat(jdbc.queryForObject("SELECT ses_message_id FROM mail_queue WHERE kind = 'INVITE'", String.class))
                .startsWith("test-");
        String audit = audits("engagement.invite-sent").getFirst();
        assertThat(audit).contains("\"actorId\":\"system\"").doesNotContain("Portfolio").doesNotContain("@example");
    }

    @Test
    void sendsAReminderWhenItFallsDue() {
        EventView event = createEvent("Lunch", Duration.ofDays(1), List.of(60));
        dispatcher.dispatchDue();

        clock.set(event.startsAt().minus(Duration.ofMinutes(60)));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        OutgoingMail reminder = channel.sent().getLast();
        assertThat(reminder.subject()).isEqualTo("Reminder: Lunch at 10:00 AM");
        assertThat(reminder.textBody()).startsWith("Your meeting starts in 1 hour.");
        assertThat(reminder.calendar()).isNull();
        assertThat(statuses(event.id(), "REMINDER")).containsExactly("SENT");
    }

    @Test
    void aReminderForAMeetingThatHasStartedIsMissedNotSent() {
        EventView event = createEvent("Started", Duration.ofDays(1), List.of(15));
        dispatcher.dispatchDue();

        // The stack was stopped across the reminder and the start (ADR-0051 decision 6).
        clock.set(event.startsAt().plusSeconds(60));
        dispatcher.dispatchDue();

        assertThat(channel.sent()).hasSize(1);
        assertThat(statuses(event.id(), "REMINDER")).containsExactly("MISSED");
        assertThat(audits("engagement.reminder-missed")).hasSize(1);
    }

    @Test
    void aCancellationReplacesAnUnsentInviteAndGoesOut() {
        EventView event = createEvent("Cancelled soon", Duration.ofDays(1), List.of(60));
        events.cancel(Tokens.AGENT, event.id());

        dispatcher.dispatchDue();

        assertThat(statuses(event.id(), "INVITE")).containsExactly("CANCELLED");
        assertThat(statuses(event.id(), "CANCEL")).containsExactly("SENT");
        assertThat(statuses(event.id(), "REMINDER")).containsExactly("CANCELLED");
        assertThat(channel.sent()).singleElement()
                .satisfies(mail -> assertThat(mail.calendar().method()).isEqualTo("CANCEL"));
    }

    @Test
    void anAgentWithoutAnAddressFailsTheRow() {
        when(agents.find(Tokens.AGENT)).thenReturn(Optional.empty());
        EventView event = createEvent("Nobody", Duration.ofDays(1), List.of());

        dispatcher.dispatchDue();

        assertThat(channel.sent()).isEmpty();
        assertThat(jdbc.queryForMap("SELECT status, last_error_code FROM mail_queue WHERE event_id = ?", event.id()))
                .containsEntry("status", "FAILED").containsEntry("last_error_code", "agent_email_unavailable");
        assertThat(audits("engagement.invite-failed")).hasSize(1);
    }

    @Test
    void aFailingSendIsRetriedAfter1And5And15MinutesThenFails() {
        channel.failWith(new MailSendException("ses_error", true));
        EventView event = createEvent("Flaky", Duration.ofDays(1), List.of());

        dispatcher.dispatchDue();
        assertThat(attempts(event.id())).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat(dispatcher.dispatchDue()).as("not before the retry time").isZero();

        for (Duration delay : List.of(Duration.ofMinutes(1), Duration.ofMinutes(5))) {
            clock.advance(delay);
            assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        }
        assertThat(attempts(event.id())).containsEntry("status", "PENDING").containsEntry("attempts", 3);
        assertThat(audits("engagement.invite-failed")).isEmpty();

        clock.advance(Duration.ofMinutes(15));
        dispatcher.dispatchDue();
        assertThat(attempts(event.id())).containsEntry("status", "FAILED").containsEntry("attempts", 4)
                .containsEntry("last_error_code", "ses_error");
        assertThat(audits("engagement.invite-failed")).hasSize(1);
    }

    @Test
    void aRejectedMessageFailsAtOnce() {
        channel.failWith(new MailSendException("ses_rejected", false));
        EventView event = createEvent("Rejected", Duration.ofDays(1), List.of());

        dispatcher.dispatchDue();

        assertThat(attempts(event.id())).containsEntry("status", "FAILED").containsEntry("attempts", 0);
    }

    @Test
    void aLeaseFromATaskThatDiedExpiresAndTheRowIsSentAgain() {
        EventView event = createEvent("Orphaned", Duration.ofDays(1), List.of());
        rows.claim(clock.instant(), 25, Duration.ofMinutes(2));

        assertThat(dispatcher.dispatchDue()).as("still leased").isZero();
        clock.advance(Duration.ofMinutes(3));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(statuses(event.id(), "INVITE")).containsExactly("SENT");
    }

    @Test
    void concurrentClaimsNeverTakeTheSameRow() throws Exception {
        for (int i = 0; i < 12; i++) {
            createEvent("Claim " + i, Duration.ofDays(1), List.of());
        }
        Instant now = clock.instant();
        Callable<List<UUID>> claimer = () -> {
            List<UUID> mine = new ArrayList<>();
            List<QueuedMail> batch;
            while (!(batch = rows.claim(now, 3, Duration.ofMinutes(2))).isEmpty()) {
                batch.forEach(mail -> mine.add(mail.id()));
            }
            return mine;
        };

        List<UUID> all = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<List<UUID>>> results = pool.invokeAll(List.of(claimer, claimer));
            for (Future<List<UUID>> result : results) {
                all.addAll(result.get());
            }
        }

        assertThat(all).hasSize(12).doesNotHaveDuplicates();
    }

    @Test
    void twoDispatchersRunningTogetherSendEachInviteOnce() throws Exception {
        for (int i = 0; i < 12; i++) {
            createEvent("Together " + i, Duration.ofDays(1), List.of());
        }
        channel.delay(20);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> run = dispatcher::dispatchDue;
            for (Future<Integer> result : pool.invokeAll(List.of(run, run))) {
                result.get();
            }
        }
        dispatcher.dispatchDue();

        Set<String> subjects = new HashSet<>();
        channel.sent().forEach(mail -> subjects.add(mail.subject()));
        assertThat(channel.sent()).hasSize(12);
        assertThat(subjects).hasSize(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_queue WHERE status = 'SENT'", Integer.class))
                .isEqualTo(12);
    }

    private Map<String, Object> attempts(UUID eventId) {
        return jdbc.queryForMap("SELECT status, attempts, last_error_code FROM mail_queue WHERE event_id = ? "
                + "AND kind = 'INVITE'", eventId);
    }
}
