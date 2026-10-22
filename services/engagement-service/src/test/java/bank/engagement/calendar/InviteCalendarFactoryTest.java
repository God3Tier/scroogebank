package bank.engagement.calendar;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InviteCalendarFactoryTest {

    private static final UUID EVENT = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private static InviteCalendarFactory.Invite invite(int sequence, boolean cancel) {
        return new InviteCalendarFactory.Invite(EVENT, "mail.example.test", sequence, cancel,
                "Portfolio review; with a comma, and a long title that has to be folded across lines by the library",
                "Bring last quarter's notes", "Level 3 meeting room", Instant.parse("2026-10-22T06:00:00Z"),
                Instant.parse("2026-10-22T07:00:00Z"), Instant.parse("2026-10-21T02:00:00Z"), "CRM Calendar",
                "no-reply@mail.example.test", "Synthetic Agent", "synthetic.agent@example.test",
                "http://localhost:5173/agent/calendar?event=" + EVENT);
    }

    private static VEvent parse(String text, Calendar[] holder) throws Exception {
        Calendar calendar = new CalendarBuilder().build(new StringReader(text));
        holder[0] = calendar;
        return calendar.<VEvent>getComponents("VEVENT").getFirst();
    }

    private static String value(VEvent event, String name) {
        return event.getProperty(name).map(Property::getValue).orElse(null);
    }

    @Test
    void requestHasAStableUidUtcTimesAndNoAlarm() throws Exception {
        String text = InviteCalendarFactory.build(invite(0, false));
        Calendar[] calendar = new Calendar[1];
        VEvent event = parse(text, calendar);

        assertThat(calendar[0].getProperty("METHOD").map(Property::getValue)).contains("REQUEST");
        assertThat(calendar[0].getProperty("VERSION").map(Property::getValue)).contains("2.0");
        assertThat(value(event, "UID")).isEqualTo(EVENT + "@mail.example.test");
        assertThat(value(event, "SEQUENCE")).isEqualTo("0");
        assertThat(value(event, "STATUS")).isEqualTo("CONFIRMED");
        assertThat(value(event, "DTSTART")).isEqualTo("20261022T060000Z");
        assertThat(value(event, "DTEND")).isEqualTo("20261022T070000Z");
        assertThat(value(event, "SUMMARY")).startsWith("Portfolio review; with a comma");
        assertThat(value(event, "DESCRIPTION")).contains("Open in the CRM: http://localhost:5173/agent/calendar");
        assertThat(value(event, "ORGANIZER")).isEqualTo("mailto:no-reply@mail.example.test");
        assertThat(value(event, "ATTENDEE")).isEqualTo("mailto:synthetic.agent@example.test");
        assertThat(text).contains("RSVP=FALSE").contains("ROLE=REQ-PARTICIPANT").contains("PARTSTAT=NEEDS-ACTION");
        assertThat(text).doesNotContain("VALARM");
        assertThat(text).contains("\r\n");
    }

    @Test
    void cancellationKeepsTheUidAndRaisesTheSequence() throws Exception {
        Calendar[] calendar = new Calendar[1];
        VEvent event = parse(InviteCalendarFactory.build(invite(2, true)), calendar);

        assertThat(calendar[0].getProperty("METHOD").map(Property::getValue)).contains("CANCEL");
        assertThat(value(event, "UID")).isEqualTo(EVENT + "@mail.example.test");
        assertThat(value(event, "SEQUENCE")).isEqualTo("2");
        assertThat(value(event, "STATUS")).isEqualTo("CANCELLED");
    }
}
