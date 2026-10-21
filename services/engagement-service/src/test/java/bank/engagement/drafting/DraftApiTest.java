package bank.engagement.drafting;

import bank.engagement.calendar.EventInput;
import bank.engagement.calendar.EventService;
import bank.engagement.calendar.EventView;
import bank.engagement.integration.clients.ClientNotFoundException;
import bank.engagement.integration.clients.ClientRecord;
import bank.engagement.integration.clients.ClientServiceUnavailableException;
import bank.engagement.notifications.MailSendException;
import bank.engagement.notifications.OutgoingMail;
import bank.engagement.testsupport.PostgresIntegrationTest;
import bank.engagement.testsupport.TestBeans;
import bank.engagement.testsupport.Tokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Drafts with AI on and the model mocked (ADR-0054). */
@TestPropertySource(properties = "engagement.ai.enabled=true")
class DraftApiTest extends PostgresIntegrationTest {

    private static final UUID CLIENT = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final ClientRecord SYNTHETIC_CLIENT =
            new ClientRecord(CLIENT, "Synthetic", "synthetic.client@example.test", "Singapore");

    private static final String SAFE_OUTPUT = """
            {"subject":"Happy Deepavali, {{client_first_name}}",
             "body":"Dear {{client_first_name}},\\n\\nWishing you a bright Deepavali.\\n\\nKind regards,\\n{{agent_name}}"}
            """;

    @MockitoBean
    private DraftModelGateway model;

    @Autowired
    private EventService events;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        when(clients.findOwned(CLIENT)).thenReturn(SYNTHETIC_CLIENT);
        when(model.invoke(anyString(), anyString())).thenReturn(new DraftModelGateway.ModelReply(SAFE_OUTPUT, 300, 90));
    }

    private static String draft(String purpose, String tone, UUID eventId) {
        return """
                {"clientId":"%s","purpose":"%s","tone":"%s","eventId":%s}
                """.formatted(CLIENT, purpose, tone, eventId == null ? "null" : "\"" + eventId + "\"");
    }

    private JsonNode create(String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/engagement/drafts").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void send(JsonNode draft, int version, int expectedStatus) throws Exception {
        mvc.perform(post("/api/engagement/drafts/" + draft.get("id").asText() + "/send").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void theModelSeesOnlyPublicDataAndPlaceholders() throws Exception {
        // An earlier meeting with this client, whose title names the client.
        EventView lunch = events.create(Tokens.AGENT, new EventInput("Lunch with Synthetic Tan", "Private notes",
                "Synthetic Tower", TestBeans.NOW.plus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC),
                TestBeans.NOW.plus(Duration.ofHours(3)).atOffset(ZoneOffset.UTC), CLIENT, List.of()));
        clock.advance(Duration.ofDays(2));

        JsonNode created = create(draft("HOLIDAY_GREETING", "WARM", null));

        assertThat(created.get("source").asText()).isEqualTo("AI");
        assertThat(created.get("sourceReason").isNull()).isTrue();
        assertThat(created.at("/holiday/name").asText()).isEqualTo("Deepavali");
        assertThat(created.at("/holiday/date").asText()).isEqualTo("2026-11-08");
        assertThat(created.get("preview").isNull()).isTrue();
        assertThat(created.get("subjectTemplate").asText()).isEqualTo("Happy Deepavali, {{client_first_name}}");

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(model).invoke(system.capture(), input.capture());
        JsonNode sent = json.readTree(input.getValue());
        List<String> fields = new ArrayList<>();
        sent.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("purpose", "purposeDescription", "tone", "language",
                "upcomingPublicHolidays", "timeSinceLastMeeting", "placeholders");
        assertThat(sent.get("timeSinceLastMeeting").asText()).isEqualTo("UNDER_1_MONTH");
        assertThat(sent.get("upcomingPublicHolidays")).hasSize(1);
        String everything = system.getValue() + input.getValue();
        for (String secret : List.of("Synthetic", "example.test", CLIENT.toString(), Tokens.AGENT,
                lunch.id().toString(), "Lunch", "Private notes", "Tower")) {
            assertThat(everything).doesNotContain(secret);
        }
    }

    @Test
    void unsafeOutputGivesTheTemplate() throws Exception {
        doReturn(new DraftModelGateway.ModelReply(
                "{\"subject\":\"Hi\",\"body\":\"Hi {{client_first_name}}, enjoy 5% off\"}", 100, 20))
                .when(model).invoke(anyString(), anyString());

        JsonNode created = create(draft("CHECK_IN", "FORMAL", null));

        assertThat(created.get("source").asText()).isEqualTo("TEMPLATE");
        assertThat(created.get("sourceReason").asText()).isEqualTo("screen_rejected");
        assertThat(created.get("subjectTemplate").asText()).isEqualTo("Checking in, {{client_first_name}}");
        assertThat(created.get("holiday").isNull()).isTrue();
    }

    @Test
    void aModelTimeoutGivesTheTemplateAndCountsTheCall() throws Exception {
        doThrow(new DraftModelException("model_timeout")).when(model).invoke(anyString(), anyString());

        JsonNode created = create(draft("PRE_HOLIDAY_REVIEW", "WARM", null));

        assertThat(created.get("source").asText()).isEqualTo("TEMPLATE");
        assertThat(created.get("sourceReason").asText()).isEqualTo("model_timeout");
        assertThat(created.get("subjectTemplate").asText()).isEqualTo("A quick catch-up before Deepavali?");
        assertThat(created.at("/holiday/name").asText()).isEqualTo("Deepavali");
        assertThat(jdbc.queryForObject("SELECT drafts FROM ai_usage", Integer.class)).isEqualTo(1);
    }

    @Test
    void theDailyCapGivesTheTemplateWithoutCallingTheModel() throws Exception {
        jdbc.update("INSERT INTO ai_usage (usage_date, agent_sub, drafts) VALUES (DATE '2026-10-21', ?, 20)",
                Tokens.AGENT);

        JsonNode created = create(draft("CHECK_IN", "WARM", null));

        assertThat(created.get("sourceReason").asText()).isEqualTo("daily_cap_reached");
        verify(model, never()).invoke(anyString(), anyString());
    }

    @Test
    void theMonthlyQuotaGivesTheTemplate() throws Exception {
        jdbc.update("INSERT INTO ai_usage (usage_date, agent_sub, drafts, input_tokens, output_tokens) "
                + "VALUES (DATE '2026-10-02', ?, 1, 250000, 50000)", Tokens.OTHER_AGENT);

        assertThat(create(draft("CHECK_IN", "WARM", null)).get("sourceReason").asText())
                .isEqualTo("monthly_quota_reached");
    }

    @Test
    void holidayPurposesNeedAHolidayForTheClientsCountry() throws Exception {
        doReturn(new ClientRecord(CLIENT, "Synthetic", "c@example.test", "Malaysia")).when(clients).findOwned(CLIENT);

        mvc.perform(post("/api/engagement/drafts").with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                .content(draft("HOLIDAY_GREETING", "FORMAL", null))).andExpect(status().isUnprocessableEntity());
        assertThat(create(draft("CHECK_IN", "FORMAL", null)).get("source").asText()).isEqualTo("AI");
    }

    @Test
    void aMeetingFollowUpNeedsAFinishedMeetingWithTheClient() throws Exception {
        mvc.perform(post("/api/engagement/drafts").with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                .content(draft("MEETING_FOLLOW_UP", "FORMAL", null))).andExpect(status().isBadRequest());

        EventView meeting = events.create(Tokens.AGENT, new EventInput("Review", null, null,
                TestBeans.NOW.plus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC),
                TestBeans.NOW.plus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC), CLIENT, List.of()));
        mvc.perform(post("/api/engagement/drafts").with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                .content(draft("MEETING_FOLLOW_UP", "FORMAL", meeting.id()))).andExpect(status().isBadRequest());

        clock.advance(Duration.ofHours(3));
        assertThat(create(draft("MEETING_FOLLOW_UP", "FORMAL", meeting.id())).get("eventId").asText())
                .isEqualTo(meeting.id().toString());
    }

    @Test
    void notTheAgentsClientIsNotFoundAndNothingIsSaved() throws Exception {
        doThrow(new ClientNotFoundException()).when(clients).findOwned(CLIENT);

        mvc.perform(post("/api/engagement/drafts").with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                .content(draft("CHECK_IN", "FORMAL", null))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_drafts", Integer.class)).isZero();
        verify(model, never()).invoke(anyString(), anyString());
    }

    @Test
    void thePreviewFillsInTheNamesButListingDoesNot() throws Exception {
        JsonNode created = create(draft("HOLIDAY_GREETING", "WARM", null));
        String path = "/api/engagement/drafts/" + created.get("id").asText();

        mvc.perform(get(path).with(Tokens.agent()))
                .andExpect(jsonPath("$.preview.subject").value("Happy Deepavali, Synthetic"))
                .andExpect(jsonPath("$.preview.body").value(org.hamcrest.Matchers.containsString("Synthetic Agent")))
                .andExpect(jsonPath("$.bodyTemplate").value(org.hamcrest.Matchers.containsString("{{agent_name}}")));
        mvc.perform(get("/api/engagement/drafts").with(Tokens.agent()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].preview").doesNotExist());

        doThrow(new ClientServiceUnavailableException("down")).when(clients).findOwned(CLIENT);
        mvc.perform(get(path).with(Tokens.agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").doesNotExist());
    }

    @Test
    void anEditedDraftIsSentOnceToTheClientWithReplyToTheAgent() throws Exception {
        JsonNode created = create(draft("HOLIDAY_GREETING", "FORMAL", null));
        String path = "/api/engagement/drafts/" + created.get("id").asText();
        mvc.perform(put(path).with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"subjectTemplate":"Deepavali wishes, {{client_first_name}}",
                         "bodyTemplate":"Dear {{client_first_name}},\\n\\nHappy Deepavali!\\n\\n{{agent_name}}",
                         "version":0}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(post(path + "/send").with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.sentAt").exists())
                .andExpect(jsonPath("$.preview.subject").value("Deepavali wishes, Synthetic"));

        OutgoingMail mail = channel.sent().getFirst();
        assertThat(mail.to()).isEqualTo("synthetic.client@example.test");
        assertThat(mail.replyTo()).isEqualTo("synthetic.agent@example.test");
        assertThat(mail.fromName()).isEqualTo("Synthetic Agent via Scrooge Bank");
        assertThat(mail.fromAddress()).isEqualTo("no-reply@mail.example.test");
        assertThat(mail.textBody()).isEqualTo("Dear Synthetic,\n\nHappy Deepavali!\n\nSynthetic Agent");

        send(created, 2, 409);
        assertThat(channel.sent()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT body_template FROM email_drafts", String.class))
                .contains("{{client_first_name}}").doesNotContain("Synthetic");
        String audit = audits("engagement.email-send").getFirst();
        assertThat(audit).contains("sesMessageId").doesNotContain("example.test").doesNotContain("Deepavali");
        mvc.perform(delete(path).with(Tokens.agent())).andExpect(status().isConflict());
    }

    @Test
    void aStaleVersionIsAConflict() throws Exception {
        send(create(draft("CHECK_IN", "FORMAL", null)), 5, 409);
        assertThat(channel.sent()).isEmpty();
    }

    @Test
    void aPlaceholderThatCannotBeFilledStopsTheSend() throws Exception {
        JsonNode created = create(draft("CHECK_IN", "FORMAL", null));
        mvc.perform(put("/api/engagement/drafts/" + created.get("id").asText()).with(Tokens.agent())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"subjectTemplate":"Hi","bodyTemplate":"Dear {{client_last_name}}","version":0}
                        """)).andExpect(status().isOk());

        send(created, 1, 422);
        assertThat(channel.sent()).isEmpty();
    }

    @Test
    void anSesFailureIs502AndTheDraftCanBeSentLater() throws Exception {
        JsonNode created = create(draft("CHECK_IN", "WARM", null));
        channel.failWith(new MailSendException("ses_rejected", false));

        send(created, 0, 502);
        mvc.perform(get("/api/engagement/drafts/" + created.get("id").asText()).with(Tokens.agent()))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        assertThat(audits("engagement.email-send-failed")).hasSize(1);

        channel.reset();
        send(created, 0, 200);
        assertThat(channel.sent()).hasSize(1);
    }

    @Test
    void aClientThatIsNoLongerTheAgentsIsNotEmailed() throws Exception {
        JsonNode created = create(draft("CHECK_IN", "WARM", null));
        doThrow(new ClientNotFoundException()).when(clients).findOwned(CLIENT);

        send(created, 0, 404);
        assertThat(channel.sent()).isEmpty();
    }

    @Test
    void aDiscardedDraftCannotBeSent() throws Exception {
        JsonNode created = create(draft("CHECK_IN", "WARM", null));
        String path = "/api/engagement/drafts/" + created.get("id").asText();

        mvc.perform(delete(path).with(Tokens.agent())).andExpect(status().isNoContent());
        mvc.perform(delete(path).with(Tokens.agent())).andExpect(status().isNoContent());
        send(created, 1, 409);
        mvc.perform(get("/api/engagement/drafts?status=DISCARDED").with(Tokens.agent()))
                .andExpect(jsonPath("$.length()").value(1));
        assertThat(audits("engagement.draft-discard")).hasSize(1);
    }

    @Test
    void anotherAgentsDraftIsNotFound() throws Exception {
        JsonNode created = create(draft("CHECK_IN", "WARM", null));
        String path = "/api/engagement/drafts/" + created.get("id").asText();

        mvc.perform(get(path).with(Tokens.agent(Tokens.OTHER_AGENT))).andExpect(status().isNotFound());
        mvc.perform(delete(path).with(Tokens.agent(Tokens.OTHER_AGENT))).andExpect(status().isNotFound());
        mvc.perform(post(path + "/send").with(Tokens.agent(Tokens.OTHER_AGENT))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/engagement/drafts").with(Tokens.agent(Tokens.OTHER_AGENT)))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/engagement/drafts").with(Tokens.admin())).andExpect(status().isForbidden());
    }
}
