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

    static final String VERSION = "drafts-v1";

    static final String SYSTEM = """
            You write short, polite emails from a relationship manager at a bank in Singapore to one of \
            their clients. Write in British English for readers in Singapore, in the requested tone.
            Address the client only as {{client_first_name}} and sign off only as {{agent_name}}. \
            Never invent names. Use no other placeholders.
            Do not mention products, rates, interest, returns, yields, investments, prices, fees, offers, \
            promotions, loans, credit, funds, stocks, insurance, accounts, balances or transactions. \
            Do not include figures with a percent sign or currency, links, email addresses or phone numbers.
            Keep the body under 150 words, in short paragraphs.
            Reply with only a JSON object and nothing else: {"subject": "...", "body": "..."}. \
            The subject is at most 120 characters. The body must contain {{client_first_name}}.""";

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
        input.put("placeholders", List.of(Placeholders.CLIENT_FIRST_NAME_TOKEN, Placeholders.AGENT_NAME_TOKEN));
        try {
            return mapper.writeValueAsString(input);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("prompt input is not serialisable", ex);
        }
    }
}
