package bank.engagement.drafting;

import bank.engagement.calendar.EventInput;
import bank.engagement.calendar.EventService;
import bank.engagement.calendar.EventView;
import bank.engagement.integration.clients.ClientNotFoundException;
import bank.engagement.integration.clients.ClientRecord;
import bank.engagement.integration.clients.ClientServiceUnavailableException;
import bank.engagement.integration.sources.SourceNotFoundException;
import bank.engagement.integration.sources.SourceUnavailableException;
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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        assertThat(created.get("subject").asText()).isEqualTo("Happy Deepavali, Synthetic");
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
                        .content(draft("HOLIDAY_GREETING", "FORMAL", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith(
                        "Public holidays are only available for clients in Singapore")));
        assertThat(create(draft("CHECK_IN", "FORMAL", null)).get("source").asText()).isEqualTo("AI");

        // Singapore, but no holiday in the 30 days from 15 August 2027.
        doReturn(SYNTHETIC_CLIENT).when(clients).findOwned(CLIENT);
        clock.set(java.time.Instant.parse("2027-08-15T02:00:00Z"));
        mvc.perform(post("/api/engagement/drafts").with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                        .content(draft("HOLIDAY_GREETING", "FORMAL", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("The client's country has no public holiday in the next 30 days"));
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
    void theNamesAreFilledInButListingDoesNotLookThemUp() throws Exception {
        JsonNode created = create(draft("HOLIDAY_GREETING", "WARM", null));
        String path = "/api/engagement/drafts/" + created.get("id").asText();

        mvc.perform(get(path).with(Tokens.agent()))
                .andExpect(jsonPath("$.subject").value("Happy Deepavali, Synthetic"))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("Synthetic Agent")))
                .andExpect(jsonPath("$.bodyTemplate").value(org.hamcrest.Matchers.containsString("{{agent_name}}")));
        mvc.perform(get("/api/engagement/drafts").with(Tokens.agent()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].subject").doesNotExist());

        doThrow(new ClientServiceUnavailableException("down")).when(clients).findOwned(CLIENT);
        mvc.perform(get(path).with(Tokens.agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").doesNotExist())
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void anEditedDraftIsSentOnceToTheClientWithReplyToTheAgent() throws Exception {
        JsonNode created = create(draft("HOLIDAY_GREETING", "FORMAL", null));
        String path = "/api/engagement/drafts/" + created.get("id").asText();
        mvc.perform(put(path).with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"subject":"Deepavali wishes, Synthetic",
                         "body":"Dear Synthetic,\\n\\nHappy Deepavali!\\n\\nSynthetic Agent",
                         "version":0}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.subject").value("Deepavali wishes, Synthetic"))
                .andExpect(jsonPath("$.subjectTemplate").value("Deepavali wishes, {{client_first_name}}"));
        assertThat(jdbc.queryForObject("SELECT body_template FROM email_drafts", String.class))
                .isEqualTo("Dear {{client_first_name}},\n\nHappy Deepavali!\n\n{{agent_name}}");

        mvc.perform(post(path + "/send").with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.sentAt").exists())
                .andExpect(jsonPath("$.edited").value(true))
                .andExpect(jsonPath("$.subject").doesNotExist())
                .andExpect(jsonPath("$.bodyTemplate").doesNotExist());

        OutgoingMail mail = channel.sent().getFirst();
        assertThat(mail.to()).isEqualTo("synthetic.client@example.test");
        assertThat(mail.replyTo()).isEqualTo("synthetic.agent@example.test");
        assertThat(mail.bcc()).isEqualTo("synthetic.agent@example.test");
        assertThat(mail.fromName()).isEqualTo("Synthetic Agent via Scrooge Bank");
        assertThat(mail.fromAddress()).isEqualTo("no-reply@mail.example.test");
        assertThat(mail.textBody()).isEqualTo("Dear Synthetic,\n\nHappy Deepavali!\n\nSynthetic Agent");

        send(created, 2, 409);
        assertThat(channel.sent()).hasSize(1);
        // The agent's copy is the Bcc; the draft keeps no text (ADR-0068 decision 4).
        assertThat(jdbc.queryForMap("SELECT subject_template, body_template FROM email_drafts").values())
                .containsOnlyNulls();
        mvc.perform(get(path).with(Tokens.agent()))
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.body").doesNotExist());
        String audit = audits("engagement.email-send").getFirst();
        assertThat(audit).contains("sesMessageId").contains("\"edited\":true").contains("\"source\":\"AI\"")
                .doesNotContain("example.test").doesNotContain("Deepavali");
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
                        {"subject":"Hi","body":"Dear {{client_last_name}}","version":0}
                        """)).andExpect(status().isOk());

        send(created, 1, 422);
        assertThat(channel.sent()).isEmpty();
    }

    @Test
    void withoutTheNamesNothingIsSaved() throws Exception {
        JsonNode created = create(draft("CHECK_IN", "FORMAL", null));
        String path = "/api/engagement/drafts/" + created.get("id").asText();
        String edit = """
                {"subject":"Hi Synthetic","body":"Dear Synthetic","version":0}
                """;

        doThrow(new ClientServiceUnavailableException("down")).when(clients).findOwned(CLIENT);
        mvc.perform(put(path).with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isServiceUnavailable());
        doReturn(SYNTHETIC_CLIENT).when(clients).findOwned(CLIENT);
        when(agents.find(Tokens.AGENT)).thenReturn(Optional.empty());
        mvc.perform(put(path).with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isServiceUnavailable());

        assertThat(jdbc.queryForObject("SELECT version FROM email_drafts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT body_template FROM email_drafts", String.class))
                .doesNotContain("Synthetic");
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
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bodyTemplate").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT body_template FROM email_drafts", String.class)).isNull();
        assertThat(audits("engagement.draft-discard")).singleElement().asString()
                .contains("\"reason\":\"agent\"");
    }

    @Test
    void anUneditedDraftIsAuditedAsSentUnchanged() throws Exception {
        JsonNode created = create(draft("CHECK_IN", "WARM", null));

        send(created, 0, 200);

        assertThat(audits("engagement.email-send")).singleElement().asString().contains("\"edited\":false")
                .contains("\"purpose\":\"CHECK_IN\"");
    }

    private static String healthDraft(String taskId) {
        return """
                {"clientId":"%s","purpose":"HEALTH_CHECK_IN","tone":"FORMAL","healthTaskId":%s}
                """.formatted(CLIENT, taskId == null ? "null" : "\"" + taskId + "\"");
    }

    private static String alertDraft(UUID alertId) {
        return """
                {"clientId":"%s","purpose":"ALERT_FOLLOW_UP","tone":"WARM","alertId":%s}
                """.formatted(CLIENT, alertId == null ? "null" : "\"" + alertId + "\"");
    }

    private void expectCreate(String body, int expectedStatus, String message) throws Exception {
        mvc.perform(post("/api/engagement/drafts").with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.message").value(message));
    }

    private ResultActions edit(String path, String subject, String body, int version) throws Exception {
        return mvc.perform(put(path).with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("subject", subject, "body", body, "version", version))));
    }

    @Test
    void aClientHealthFollowUpNeedsTheAgentsTaskForThisClient() throws Exception {
        expectCreate(healthDraft(null), 400, "healthTaskId is required for HEALTH_CHECK_IN");
        expectCreate("""
                {"clientId":"%s","purpose":"CHECK_IN","tone":"FORMAL","healthTaskId":"task-000001"}
                """.formatted(CLIENT), 400, "healthTaskId is only for HEALTH_CHECK_IN");

        doThrow(new SourceNotFoundException()).when(sources).healthTaskClient("task-000001");
        expectCreate(healthDraft("task-000001"), 400, "healthTaskId must be one of your Client Health tasks");
        doReturn(UUID.randomUUID().toString()).when(sources).healthTaskClient("task-000001");
        expectCreate(healthDraft("task-000001"), 400, "The Client Health task is not for this client");
        doThrow(new SourceUnavailableException("down")).when(sources).healthTaskClient("task-000001");
        expectCreate(healthDraft("task-000001"), 503, "Client Health is unavailable; nothing was saved");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_drafts", Integer.class)).isZero();

        doReturn(CLIENT.toString()).when(sources).healthTaskClient("task-000001");
        JsonNode created = create(healthDraft("task-000001"));

        assertThat(created.get("healthTaskId").asText()).isEqualTo("task-000001");
        assertThat(created.get("alertId").isNull()).isTrue();
        assertThat(audits("engagement.draft-generate")).singleElement().asString()
                .contains("\"healthTaskId\":\"task-000001\"");
    }

    @Test
    void anAlertFollowUpIsReplacedByNewsAlertDrafts() throws Exception {
        UUID alert = UUID.fromString("50000000-0000-4000-8000-000000000001");
        expectCreate(alertDraft(alert), 400, "Alert follow-ups were replaced by News Alert drafts; open them from Event Alerts");
        expectCreate(alertDraft(null), 400, "Alert follow-ups were replaced by News Alert drafts; open them from Event Alerts");
        expectCreate("""
                {"clientId":"%s","purpose":"NEWS_ALERT","tone":"NONE"}
                """.formatted(CLIENT), 400, "News Alert drafts are made from approved reports, not written here");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_drafts", Integer.class)).isZero();
    }

    @Test
    void theModelGetsTheDetailsSlotButNothingFromTheTask() throws Exception {
        doReturn(CLIENT.toString()).when(sources).healthTaskClient("task-000001");
        doReturn(new DraftModelGateway.ModelReply("""
                {"subject":"Keeping in touch, {{client_first_name}}",
                 "body":"Dear {{client_first_name}},\\n\\n{{agent_details}}\\n\\nKind regards,\\n{{agent_name}}"}
                """, 300, 90)).when(model).invoke(anyString(), anyString());

        JsonNode created = create(healthDraft("task-000001"));

        assertThat(created.get("source").asText()).isEqualTo("AI");
        assertThat(created.get("body").asText()).contains("[Add your details here]");
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(model).invoke(anyString(), input.capture());
        assertThat(json.readTree(input.getValue()).get("placeholders").toString()).contains("{{agent_details}}");
        assertThat(input.getValue()).doesNotContain("task-000001").doesNotContain(CLIENT.toString());
    }

    @Test
    void anUntouchedDetailsSlotStopsTheSendUntilTheAgentFillsItIn() throws Exception {
        doReturn(CLIENT.toString()).when(sources).healthTaskClient("task-000001");
        JsonNode created = create(healthDraft("task-000001"));
        String path = "/api/engagement/drafts/" + created.get("id").asText();
        String subject = created.get("subject").asText();
        String body = created.get("body").asText();
        assertThat(created.get("source").asText()).isEqualTo("TEMPLATE");
        assertThat(body).contains("[Add your details here]");

        // Saved untouched, the slot is stored as its placeholder again, and nothing changes.
        edit(path, subject, body, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edited").value(false))
                .andExpect(jsonPath("$.version").value(0));
        assertThat(jdbc.queryForObject("SELECT body_template FROM email_drafts", String.class))
                .contains("{{agent_details}}");
        mvc.perform(post(path + "/send").with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Replace [Add your details here] with your details, or delete it"));
        assertThat(channel.sent()).isEmpty();

        edit(path, subject, body.replace("[Add your details here]", "Your annual review is due."), 0)
                .andExpect(jsonPath("$.edited").value(true))
                .andExpect(jsonPath("$.version").value(1));
        send(created, 1, 200);

        assertThat(channel.sent().getFirst().textBody()).contains("Your annual review is due.")
                .doesNotContain("[Add your details here]").doesNotContain("{{");
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
