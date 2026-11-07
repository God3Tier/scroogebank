package bank.engagement.drafting;

import bank.engagement.holidays.HolidayView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The versioned prompt (ADR-0054 decision 3). {@link #userInput} builds the
 * only data the model receives: purpose, tone, language, public holidays, a
 * banded recency and the placeholders (ADR-0054 decision 2). Nothing about the
 * client or what the agent typed is a parameter here.
 */
@UtilityClass
final class DraftPrompt {

    /**
     * v3 names {@code {{agent_details}}} only to the purposes that have the
     * slot (ADR-0068 decision 2): v2 named it to every purpose, and Nova Lite
     * then wrote it into most check-ins, which the screen rejects.
     */
    static final String VERSION = "drafts-v4";

    private static final String INTRO = """
            You write short, polite emails from a relationship manager at a bank in Singapore to one of \
            their clients. Write in British English for readers in Singapore, in the requested tone.
            Address the client only as {{client_first_name}} and sign off only as {{agent_name}}. \
            Never invent names. Use only the placeholders you are given.
            """;

    /**
     * v4 makes the wording depend on the inputs the model already gets
     * (ADR-0054 decision 2) and bans the stock phrases Nova Lite used in
     * almost every v3 draft. Nothing new is sent.
     */
    private static final String STYLE = """
            Make the email read as written for this occasion, not from a template:
            - FORMAL: courteous, complete sentences, no contractions; open with "Dear {{client_first_name}},". \
            WARM: friendly and conversational, contractions are fine; open with "Hi {{client_first_name}},".
            - After the greeting, start with a sentence about the occasion itself, not about the client's \
            wellbeing.
            - Let timeSinceLastMeeting shape the wording, in your own words and without dates. UNDER_1_MONTH: \
            you spoke recently, so never say it has been a while or some time. 1_TO_3_MONTHS: it has been a \
            little while. OVER_3_MONTHS: it has been quite some time. NONE: say nothing about when you last met.
            - If upcomingPublicHolidays is not empty, you may mention the nearest one by name in one short, \
            natural sentence. Never invent a holiday or a date.
            - End the main text with one easy next step phrased as a question, such as whether a short call, \
            a coffee or a meeting at a time that suits them would help. Do not propose a date or time.
            - The subject should fit this purpose and tone, not a generic "Checking in". Neither the subject \
            nor the body may suggest that anything about the client, their finances, health or activity has \
            been noticed or reviewed.
            - These stock phrases make an email sound generic, so never write them: "I hope you are well", \
            "I trust you are well", "I hope this message finds you well", "touch base", "reach out", \
            "circle back", "do not hesitate", "just checking in". Say the plain thing instead, for example \
            "speak" or "get in touch" rather than "touch base" or "reach out".
            """;

    private static final String DETAILS_SLOT = """
            Put {{agent_details}} exactly once in the body, on a line of its own, where the relationship \
            manager will add their own reason for writing. Do not guess or describe that reason yourself.
            """;

    private static final String RULES = """
            Do not mention products, rates, interest, returns, yields, investments, prices, fees, offers, \
            promotions, loans, credit, funds, stocks, insurance, accounts, balances or transactions. \
            Never use these words in any sense, even an everyday one: offer, interest, return, rate, account, \
            balance, credit, share, fund, price, fee, deposit, bonus, discount, guarantee, invest. \
            For example, write "suggest" not "offer", "on your way back" not "on your return", \
            and "useful" not "of interest".
            Do not include figures with a percent sign or currency, links, email addresses or phone numbers.
            Keep the body between 60 and 120 words, in two to four short paragraphs separated by a blank line.
            Reply with only a JSON object and nothing else: {"subject": "...", "body": "..."}. \
            Inside the JSON strings write each line break as \\n, never as \\\\n. \
            The subject is at most 120 characters. The body must contain {{client_first_name}}.""";

    /** The system prompt for a purpose; only a purpose with a details slot hears of it. */
    static String system(DraftPurpose purpose) {
        return INTRO + STYLE + (purpose.hasDetailsSlot() ? DETAILS_SLOT : "") + RULES;
    }

    static String userInput(DraftPurpose purpose, DraftTone tone, List<HolidayView> holidays,
            MeetingRecency recency, ObjectMapper mapper) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("purpose", purpose.name());
        input.put("purposeDescription", purpose.description());
        input.put("tone", tone.name());
        input.put("language", "en-SG");
        input.put("upcomingPublicHolidays", holidays.stream()
                .map(holiday -> Map.of("name", holiday.name(), "date", holiday.date().toString()))
                .toList());
        input.put("timeSinceLastMeeting", recency.label());
        input.put("placeholders", purpose.hasDetailsSlot()
                ? List.of(Placeholders.CLIENT_FIRST_NAME_TOKEN, Placeholders.AGENT_NAME_TOKEN,
                        Placeholders.AGENT_DETAILS_TOKEN)
                : List.of(Placeholders.CLIENT_FIRST_NAME_TOKEN, Placeholders.AGENT_NAME_TOKEN));
        try {
            return mapper.writeValueAsString(input);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("prompt input is not serialisable", ex);
        }
    }
}
