package bank.engagement.drafting;

import bank.engagement.holidays.HolidayView;
import lombok.experimental.UtilityClass;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * One fixed template per purpose and tone, with a holiday slot (ADR-0054
 * decision 5). Used whenever the model is off, capped, slow, failing or its
 * output fails the screen. Every template passes the screen itself.
 */
@UtilityClass
final class DraftTemplates {

    static final String VERSION = "templates-v2";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);

    record Text(String subject, String body) {
    }

    /** @param holiday required for the holiday purposes, ignored for the others */
    static Text render(DraftPurpose purpose, DraftTone tone, HolidayView holiday) {
        String name = holiday == null ? "" : holiday.name();
        String day = holiday == null ? "" : DAY.format(holiday.date());
        boolean formal = tone == DraftTone.FORMAL;
        return switch (purpose) {
            case CHECK_IN -> formal
                    ? new Text("Checking in, {{client_first_name}}", """
                            Dear {{client_first_name}},

                            I hope this note finds you well. I wanted to check in and see how things have been \
                            since we last spoke.

                            If there is anything you would like to discuss, I would be glad to arrange a time \
                            that suits you.

                            Kind regards,
                            {{agent_name}}""")
                    : new Text("Just checking in, {{client_first_name}}", """
                            Hi {{client_first_name}},

                            I hope you have been keeping well! It has been a little while, so I thought I would \
                            drop you a quick note to say hello.

                            Do let me know if you would like to catch up over coffee or a call.

                            Best wishes,
                            {{agent_name}}""");
            case MEETING_FOLLOW_UP -> formal
                    ? new Text("Thank you for meeting, {{client_first_name}}", """
                            Dear {{client_first_name}},

                            Thank you for taking the time to meet with me. I appreciated our conversation.

                            If any questions come up in the meantime, please do not hesitate to get in touch.

                            Kind regards,
                            {{agent_name}}""")
                    : new Text("Great to catch up, {{client_first_name}}", """
                            Hi {{client_first_name}},

                            It was lovely to catch up with you. Thank you for your time.

                            Please get in touch whenever anything comes to mind, and I look forward to speaking \
                            again soon.

                            Best wishes,
                            {{agent_name}}""");
            case HOLIDAY_GREETING -> formal
                    ? new Text(name + " greetings, {{client_first_name}}", """
                            Dear {{client_first_name}},

                            With %s coming up on %s, I would like to extend my warmest wishes to you and your \
                            family.

                            I hope you have a restful and enjoyable holiday.

                            Kind regards,
                            {{agent_name}}""".formatted(name, day))
                    : new Text("Happy " + name + ", {{client_first_name}}!", """
                            Hi {{client_first_name}},

                            %s is just around the corner on %s, and I wanted to wish you and your loved ones a \
                            wonderful celebration.

                            Enjoy the break!

                            Best wishes,
                            {{agent_name}}""".formatted(name, day));
            case PRE_HOLIDAY_REVIEW -> formal
                    ? new Text("Catching up before " + name, """
                            Dear {{client_first_name}},

                            As %s approaches on %s, I wondered whether you would like to meet beforehand to \
                            review how things are going and plan ahead.

                            Please let me know a time that suits you, and I will arrange it.

                            Kind regards,
                            {{agent_name}}""".formatted(name, day))
                    : new Text("A quick catch-up before " + name + "?", """
                            Hi {{client_first_name}},

                            With %s coming up on %s, would you like a quick catch-up before the holiday? It \
                            could be a good moment to look back on the year and plan ahead together.

                            Just let me know what works for you.

                            Best wishes,
                            {{agent_name}}""".formatted(name, day));
            // The two follow-ups from another feature: the agent writes the
            // reason at the slot (ADR-0068 decision 2).
            case HEALTH_CHECK_IN -> formal
                    ? new Text("Keeping in touch, {{client_first_name}}", """
                            Dear {{client_first_name}},

                            I hope you are keeping well. I wanted to get in touch about the following:

                            {{agent_details}}

                            I would be glad to arrange a short conversation at a time that suits you.

                            Kind regards,
                            {{agent_name}}""")
                    : new Text("Catching up, {{client_first_name}}", """
                            Hi {{client_first_name}},

                            I hope all is well with you! I wanted to reach out about something:

                            {{agent_details}}

                            Would you be up for a quick call or a coffee? Just let me know what suits you.

                            Best wishes,
                            {{agent_name}}""");
            case ALERT_FOLLOW_UP -> formal
                    ? new Text("Recent developments, {{client_first_name}}", """
                            Dear {{client_first_name}},

                            There have been some recent developments that you may wish to discuss:

                            {{agent_details}}

                            If it would be helpful, I would be happy to arrange a time to talk them through.

                            Kind regards,
                            {{agent_name}}""")
                    : new Text("Something you may want to discuss, {{client_first_name}}", """
                            Hi {{client_first_name}},

                            Something has come up recently that I thought you might like to talk about:

                            {{agent_details}}

                            Happy to have a quick chat whenever suits you.

                            Best wishes,
                            {{agent_name}}""");
        };
    }
}
