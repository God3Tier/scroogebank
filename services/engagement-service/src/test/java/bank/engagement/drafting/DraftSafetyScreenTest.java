package bank.engagement.drafting;

import bank.engagement.holidays.HolidayView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DraftSafetyScreenTest {

    private final DraftSafetyScreen screen = new DraftSafetyScreen(new ObjectMapper());

    private static String json(String subject, String body) {
        return "{\"subject\":\"" + subject + "\",\"body\":\"" + body + "\"}";
    }

    @Test
    void acceptsAPlainDraftWithThePlaceholders() {
        DraftSafetyScreen.Result result = screen.inspect(json("Happy Deepavali, {{client_first_name}}",
                "Dear {{client_first_name}},\\n\\nWishing you a bright Deepavali.\\n\\nKind regards,\\n{{agent_name}}"),
                DraftPurpose.HOLIDAY_GREETING);

        assertThat(result.accepted()).isTrue();
        assertThat(result.subject()).isEqualTo("Happy Deepavali, {{client_first_name}}");
        assertThat(result.body()).startsWith("Dear {{client_first_name}},");
    }

    @Test
    void aFollowUpFromAnotherFeatureNeedsExactlyOneDetailsSlotInTheBody() {
        String withSlot = json("Keeping in touch",
                "Dear {{client_first_name}},\\n\\n{{agent_details}}\\n\\nKind regards,\\n{{agent_name}}");
        String withoutSlot = json("Keeping in touch", "Dear {{client_first_name}},\\n\\n{{agent_name}}");
        String twoSlots = json("Keeping in touch", "Dear {{client_first_name}}, {{agent_details}} {{agent_details}}");
        String slotInSubject = json("About {{agent_details}}", "Dear {{client_first_name}}, {{agent_details}}");

        for (DraftPurpose purpose : List.of(DraftPurpose.HEALTH_CHECK_IN, DraftPurpose.ALERT_FOLLOW_UP)) {
            assertThat(screen.inspect(withSlot, purpose).accepted()).isTrue();
            assertThat(screen.inspect(withoutSlot, purpose).reason()).isEqualTo("missing_details_slot");
            assertThat(screen.inspect(twoSlots, purpose).reason()).isEqualTo("missing_details_slot");
            assertThat(screen.inspect(slotInSubject, purpose).reason()).isEqualTo("unknown_placeholder");
        }
        assertThat(screen.inspect(withSlot, DraftPurpose.CHECK_IN).reason()).isEqualTo("unknown_placeholder");
    }

    /** Nova Lite sometimes writes \\n in its JSON, which parses to a backslash and an n, not a line break. */
    @Test
    void turnsDoubleEscapedLineBreaksIntoLineBreaks() {
        DraftSafetyScreen.Result result = screen.inspect(json("Keeping in touch",
                "Dear {{client_first_name}},\\\\n\\\\n{{agent_details}}\\\\r\\\\n\\\\nWarm regards,\\\\n{{agent_name}}"),
                DraftPurpose.HEALTH_CHECK_IN);

        assertThat(result.accepted()).isTrue();
        assertThat(result.body())
                .isEqualTo("Dear {{client_first_name}},\n\n{{agent_details}}\n\nWarm regards,\n{{agent_name}}")
                .doesNotContain("\\");
    }

    @Test
    void acceptsJsonInsideAMarkdownFence() {
        String fenced = "```json\n" + json("Hello", "Hi {{client_first_name}}, all the best.") + "\n```";

        assertThat(screen.inspect(fenced, DraftPurpose.CHECK_IN).accepted()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "invalid_json|not json at all",
            "invalid_json|{\"subject\":\"Hi\",\"body\":\"Hi {{client_first_name}}\",\"extra\":\"field\"}",
            "invalid_json|{\"subject\":\"Hi\"}",
            "missing_placeholder|{\"subject\":\"Hi\",\"body\":\"Dear client, hello.\"}",
            "unknown_placeholder|{\"subject\":\"Hi\",\"body\":\"Hi {{client_first_name}} {{client_email}}\"}",
            "unknown_placeholder|{\"subject\":\"Hi {{\",\"body\":\"Hi {{client_first_name}}\"}",
            "url|{\"subject\":\"Hi\",\"body\":\"Hi {{client_first_name}}, see https://example.test/x\"}",
            "url|{\"subject\":\"Hi\",\"body\":\"Hi {{client_first_name}}, visit scrooge.com today\"}",
            "email|{\"subject\":\"Hi\",\"body\":\"Hi {{client_first_name}}, write to someone@example.test\"}",
            "phone|{\"subject\":\"Hi\",\"body\":\"Hi {{client_first_name}}, call +65 6123 4567\"}",
            "figure|{\"subject\":\"Hi\",\"body\":\"Hi {{client_first_name}}, enjoy 5% more\"}",
            "figure|{\"subject\":\"Hi\",\"body\":\"Hi {{client_first_name}}, only S$100\"}",
            "figure|{\"subject\":\"Hi\",\"body\":\"Hi {{client_first_name}}, worth 200 SGD\"}",
            "blocked_term|{\"subject\":\"Hi\",\"body\":\"Hi {{client_first_name}}, our new investment plan\"}",
            "blocked_term|{\"subject\":\"Great rates\",\"body\":\"Hi {{client_first_name}}\"}",
            "blocked_term|{\"subject\":\"Hi\",\"body\":\"Hi {{client_first_name}}, about your account balance\"}",
            "blocked_term|{\"subject\":\"Hi\",\"body\":\"Hi {{client_first_name}}, a special offer\"}",
    })
    void rejects(String reason, String output) {
        DraftSafetyScreen.Result result = screen.inspect(output, DraftPurpose.CHECK_IN);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).isEqualTo(reason);
    }

    @Test
    void rejectsEmptyAndOverlongOutput() {
        assertThat(screen.inspect("", DraftPurpose.CHECK_IN).reason()).isEqualTo("empty_output");
        assertThat(screen.inspect(json("x".repeat(121), "Hi {{client_first_name}}"), DraftPurpose.CHECK_IN).reason())
                .isEqualTo("length");
        assertThat(screen.inspect(json("Hi", "Hi {{client_first_name}} " + "a".repeat(1_500)), DraftPurpose.CHECK_IN)
                .reason()).isEqualTo("length");
    }

    /** The fallback must itself be safe: every template passes the screen. */
    @Test
    void everyTemplatePassesTheScreen() {
        List<HolidayView> holidays = List.of(
                new HolidayView(UUID.randomUUID(), "SG", LocalDate.of(2026, 11, 8), "Deepavali", false),
                new HolidayView(UUID.randomUUID(), "SG", LocalDate.of(2027, 2, 6), "Chinese New Year", false),
                new HolidayView(UUID.randomUUID(), "SG", LocalDate.of(2027, 1, 1), "New Year's Day", false),
                new HolidayView(UUID.randomUUID(), "SG", LocalDate.of(2026, 5, 27), "Hari Raya Haji", false));
        // News Alert drafts are built from approved reports, never from a template or a tone.
        for (DraftPurpose purpose : DraftPurpose.values()) {
            if (purpose == DraftPurpose.NEWS_ALERT) continue;
            for (DraftTone tone : List.of(DraftTone.FORMAL, DraftTone.WARM)) {
                for (HolidayView holiday : holidays) {
                    DraftTemplates.Text text = DraftTemplates.render(purpose, tone, holiday);
                    DraftSafetyScreen.Result result = screen.check(text.subject(), text.body(), purpose);
                    assertThat(result.accepted())
                            .as("%s %s %s: %s", purpose, tone, holiday.name(), result.reason())
                            .isTrue();
                }
            }
        }
    }
}
