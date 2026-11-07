package bank.engagement.calendar;

import bank.engagement.config.TimeConfiguration;
import bank.engagement.integration.identity.AgentDirectory;
import bank.engagement.integration.identity.AgentProfile;
import bank.engagement.notifications.Composition;
import bank.engagement.notifications.HtmlText;
import bank.engagement.notifications.MailComposer;
import bank.engagement.notifications.MailKind;
import bank.engagement.notifications.MailProperties;
import bank.engagement.notifications.MailStatus;
import bank.engagement.notifications.OutgoingMail;
import bank.engagement.notifications.QueuedMail;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Builds the messages the dispatcher sends to agents: calendar invites,
 * updates and cancellations (ADR-0052), and reminders. Only what the agent
 * typed appears, and only in the agent's own mailbox (ADR-0052 decision 4).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class CalendarMailComposer implements MailComposer {

    static final String FROM_NAME = "CRM Calendar";

    private static final DateTimeFormatter WHEN = DateTimeFormatter
            .ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH)
            .withZone(TimeConfiguration.SINGAPORE);
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("h:mm a", Locale.ENGLISH)
            .withZone(TimeConfiguration.SINGAPORE);

    private final CalendarEventRepository events;
    private final AgentDirectory agents;
    private final MailProperties properties;

    @Override
    @Transactional(readOnly = true)
    public Composition compose(QueuedMail mail, Instant now) {
        Optional<CalendarEvent> found = events.findById(mail.eventId());
        if (found.isEmpty()) {
            return new Composition.Skip(MailStatus.CANCELLED);
        }
        CalendarEvent event = found.get();
        if (mail.kind() != MailKind.CANCEL && event.isCancelled()) {
            return new Composition.Skip(MailStatus.CANCELLED);
        }
        if (mail.kind() == MailKind.REMINDER && !now.isBefore(event.getStartsAt())) {
            return new Composition.Skip(MailStatus.MISSED);
        }
        Optional<AgentProfile> agent = agents.find(event.getOwnerAgentSub());
        if (agent.isEmpty() || !agent.get().hasEmail()) {
            return new Composition.Fail("agent_email_unavailable");
        }
        if (!agent.get().enabled()) {
            return new Composition.Fail("agent_disabled");
        }
        return new Composition.Ready(mail.kind() == MailKind.REMINDER
                ? reminder(event, mail.offsetMinutes(), agent.get())
                : invite(event, mail, agent.get(), now));
    }

    private OutgoingMail invite(CalendarEvent event, QueuedMail mail, AgentProfile agent, Instant now) {
        boolean cancel = mail.kind() == MailKind.CANCEL;
        String ics = InviteCalendarFactory.build(new InviteCalendarFactory.Invite(event.getId(),
                properties.mailDomain(), mail.icsSequence(), cancel, event.getTitle(), event.getDescription(),
                event.getLocation(), event.getStartsAt(), event.getEndsAt(), now, FROM_NAME,
                properties.fromAddress(), agent.displayName(), agent.email(), eventUrl(event)));
        String prefix = switch (mail.kind()) {
            case INVITE -> "Invitation";
            case UPDATE -> "Updated";
            default -> "Cancelled";
        };
        String subject = prefix + ": " + event.getTitle() + " @ " + WHEN.format(event.getStartsAt());
        StringBuilder text = new StringBuilder()
                .append(cancel ? "This meeting has been cancelled.\n\n" : "")
                .append(event.getTitle()).append('\n')
                .append("When: ").append(when(event)).append('\n');
        if (event.getLocation() != null) {
            text.append("Where: ").append(event.getLocation()).append('\n');
        }
        if (event.getDescription() != null) {
            text.append('\n').append(event.getDescription()).append('\n');
        }
        text.append("\nOpen in the CRM: ").append(eventUrl(event)).append("\n\n")
                .append("This comes from the CRM calendar. Changes you make in your own calendar are not "
                        + "sent back to the CRM; change the meeting in the CRM instead.\n");
        return new OutgoingMail(FROM_NAME, properties.fromAddress(), agent.email(), null, subject,
                text.toString(), HtmlText.of(text.toString()),
                new OutgoingMail.CalendarPart(cancel ? "CANCEL" : "REQUEST", ics));
    }

    private OutgoingMail reminder(CalendarEvent event, int offsetMinutes, AgentProfile agent) {
        String subject = "Reminder: " + event.getTitle() + " at " + TIME.format(event.getStartsAt());
        StringBuilder text = new StringBuilder()
                .append("Your meeting starts in ").append(offsetLabel(offsetMinutes)).append(".\n\n")
                .append(event.getTitle()).append('\n')
                .append("When: ").append(when(event)).append('\n');
        if (event.getLocation() != null) {
            text.append("Where: ").append(event.getLocation()).append('\n');
        }
        text.append("\nOpen in the CRM: ").append(eventUrl(event)).append('\n');
        return new OutgoingMail(FROM_NAME, properties.fromAddress(), agent.email(), null, subject,
                text.toString(), HtmlText.of(text.toString()), null);
    }

    private String eventUrl(CalendarEvent event) {
        return StringUtils.trimTrailingCharacter(properties.crmAppUrl(), '/') + "/agent/calendar?event=" + event.getId();
    }

    private static String when(CalendarEvent event) {
        return WHEN.format(event.getStartsAt()) + " to " + TIME.format(event.getEndsAt()) + " (Singapore time)";
    }

    static String offsetLabel(int minutes) {
        return switch (minutes) {
            case 1440 -> "1 day";
            case 60 -> "1 hour";
            default -> minutes + " minutes";
        };
    }
}
