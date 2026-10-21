package bank.engagement.calendar;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.Cn;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.parameter.Role;
import net.fortuna.ical4j.model.parameter.Rsvp;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.Organizer;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;
import net.fortuna.ical4j.model.property.immutable.ImmutableStatus;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The iCalendar text of an invite, update or cancellation (ADR-0052 decisions
 * 1 and 2). The {@code UID} never changes, so mail programs match updates and
 * cancellations to the original; times are UTC; there is no {@code VALARM},
 * because the CRM sends its own reminders.
 */
final class InviteCalendarFactory {

    static final String PRODUCT_ID = "-//Scrooge Global Bank//CRM Calendar//EN";

    private InviteCalendarFactory() {
    }

    /**
     * @param cancel        {@code METHOD:CANCEL} and {@code STATUS:CANCELLED}; otherwise
     *                      {@code METHOD:REQUEST} and {@code STATUS:CONFIRMED}
     * @param eventUrl      the event in the CRM, appended to the description
     */
    record Invite(UUID eventId, String mailDomain, int sequence, boolean cancel, String title, String description,
            String location, Instant startsAt, Instant endsAt, Instant stamp, String organizerName,
            String organizerAddress, String attendeeName, String attendeeAddress, String eventUrl) {
    }

    static String uid(UUID eventId, String mailDomain) {
        return eventId + "@" + mailDomain;
    }

    static String build(Invite invite) {
        VEvent event = new VEvent(invite.startsAt(), invite.endsAt(), invite.title());
        event.add(new Uid(uid(invite.eventId(), invite.mailDomain())));
        event.add(new DtStamp(invite.stamp()));
        event.add(new Sequence(invite.sequence()));
        event.add(invite.cancel() ? ImmutableStatus.VEVENT_CANCELLED : ImmutableStatus.VEVENT_CONFIRMED);
        if (invite.location() != null) {
            event.add(new Location(invite.location()));
        }
        String description = (invite.description() == null ? "" : invite.description() + "\n\n")
                + "Open in the CRM: " + invite.eventUrl();
        event.add(new Description(description));
        event.add(new Organizer(parameters(new Cn(invite.organizerName())),
                URI.create("mailto:" + invite.organizerAddress())));
        List<Parameter> attendee = new ArrayList<>(List.of(Role.REQ_PARTICIPANT, PartStat.NEEDS_ACTION,
                new Rsvp(false)));
        if (invite.attendeeName() != null) {
            attendee.add(new Cn(invite.attendeeName()));
        }
        event.add(new Attendee(new ParameterList(attendee), URI.create("mailto:" + invite.attendeeAddress())));

        Calendar calendar = new Calendar()
                .withProdId(PRODUCT_ID)
                .withDefaults()
                .withProperty(invite.cancel() ? ImmutableMethod.CANCEL : ImmutableMethod.REQUEST)
                .withComponent(event)
                .getFluentTarget();
        return calendar.toString();
    }

    private static ParameterList parameters(Parameter... parameters) {
        return new ParameterList(List.of(parameters));
    }
}
