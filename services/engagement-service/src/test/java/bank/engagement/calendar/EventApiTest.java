package bank.engagement.calendar;

import bank.engagement.integration.clients.ClientNotFoundException;
import bank.engagement.integration.clients.ClientRecord;
import bank.engagement.integration.clients.ClientServiceUnavailableException;
import bank.engagement.testsupport.PostgresIntegrationTest;
import bank.engagement.testsupport.Tokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventApiTest extends PostgresIntegrationTest {

    private static final UUID CLIENT = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Autowired
    private ObjectMapper json;

    /** The caller's events from 20 October to 20 November, Singapore time. */
    private static MockHttpServletRequestBuilder window() {
        return get("/api/engagement/events")
                .param("from", "2026-10-20T00:00:00+08:00")
                .param("to", "2026-11-20T00:00:00+08:00");
    }

    private static String event(String title, String start, String end, String offsets, String client) {
        return """
                {"title":"%s","description":"Bring the synthetic notes","location":"Level 3",
                 "startsAt":"%s","endsAt":"%s","reminderOffsetsMinutes":%s,"clientId":%s}
                """.formatted(title, start, end, offsets, client == null ? "null" : "\"" + client + "\"");
    }

    private static String meeting(String title) {
        return event(title, "2026-10-22T14:00:00+08:00", "2026-10-22T15:00:00+08:00", "[1440, 15]", null);
    }

    private JsonNode create(String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/engagement/events").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String update(JsonNode event, String title, String offsets, int version) {
        return """
                {"title":"%s","description":"Bring the synthetic notes","location":"Level 3",
                 "startsAt":"%s","endsAt":"%s","reminderOffsetsMinutes":%s,"version":%d}
                """.formatted(title, event.get("startsAt").asText(), event.get("endsAt").asText(), offsets, version);
    }

    @Test
    void requiresAnAgentToken() throws Exception {
        mvc.perform(window()).andExpect(status().isUnauthorized());
        mvc.perform(window().with(Tokens.admin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void createsAnEventQueuesItsInviteAndSchedulesReminders() throws Exception {
        MvcResult result = mvc.perform(post("/api/engagement/events").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON).content(meeting("Quarterly catch-up")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/engagement/events/")))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.startsAt").value("2026-10-22T06:00:00Z"))
                .andExpect(jsonPath("$.invite.status").value("PENDING"))
                .andExpect(jsonPath("$.invite.sequence").value(0))
                .andExpect(jsonPath("$.reminders.length()").value(2))
                .andExpect(jsonPath("$.reminders[0].offsetMinutes").value(1440))
                .andExpect(jsonPath("$.reminders[0].dueAt").value("2026-10-21T06:00:00Z"))
                .andExpect(jsonPath("$.reminders[1].offsetMinutes").value(15))
                .andExpect(jsonPath("$.reminders[1].status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_queue", Integer.class)).isEqualTo(3);
        String audit = audits("engagement.event-create").getFirst();
        assertThat(audit).contains(json.readTree(result.getResponse().getContentAsString()).get("id").asText())
                .contains(Tokens.AGENT)
                .doesNotContain("Quarterly").doesNotContain("synthetic notes").doesNotContain("Level 3");
    }

    @Test
    void aReminderWhoseTimeHasPassedIsMissed() throws Exception {
        JsonNode created = create(event("Soon", "2026-10-21T10:30:00+08:00", "2026-10-21T11:00:00+08:00", "[60]", null));

        assertThat(created.at("/reminders/0/status").asText()).isEqualTo("MISSED");
    }

    @Test
    void linksOnlyTheAgentsOwnClientAndSavesNothingOtherwise() throws Exception {
        String body = event("Client lunch", "2026-10-22T12:00:00+08:00", "2026-10-22T13:00:00+08:00", "[]",
                CLIENT.toString());

        doThrow(new ClientNotFoundException()).when(clients).findOwned(CLIENT);
        mvc.perform(post("/api/engagement/events").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Client not found"));

        doThrow(new ClientServiceUnavailableException("down")).when(clients).findOwned(CLIENT);
        mvc.perform(post("/api/engagement/events").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_outbox", Integer.class)).isZero();

        doReturn(new ClientRecord(CLIENT, "Synthetic", "c@example.test", "SG")).when(clients).findOwned(CLIENT);
        assertThat(create(body).get("clientId").asText()).isEqualTo(CLIENT.toString());
    }

    @Test
    void rejectsEventsThatBreakTheContract() throws Exception {
        String[] invalid = {
                event("Past", "2026-10-20T10:00:00+08:00", "2026-10-20T11:00:00+08:00", "[]", null),
                event("Backwards", "2026-10-22T11:00:00+08:00", "2026-10-22T10:00:00+08:00", "[]", null),
                event("Too long", "2026-10-22T10:00:00+08:00", "2026-10-23T10:01:00+08:00", "[]", null),
                event("Odd reminder", "2026-10-22T10:00:00+08:00", "2026-10-22T11:00:00+08:00", "[10]", null),
                event("Three", "2026-10-22T10:00:00+08:00", "2026-10-22T11:00:00+08:00", "[15, 30, 60]", null),
                event(" ", "2026-10-22T10:00:00+08:00", "2026-10-22T11:00:00+08:00", "[]", null),
                event("x".repeat(121), "2026-10-22T10:00:00+08:00", "2026-10-22T11:00:00+08:00", "[]", null),
        };
        for (String body : invalid) {
            mvc.perform(post("/api/engagement/events").with(Tokens.agent())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM events", Integer.class)).isZero();
    }

    @Test
    void anotherAgentsEventIsNotFound() throws Exception {
        JsonNode created = create(meeting("Mine"));
        String path = "/api/engagement/events/" + created.get("id").asText();

        mvc.perform(get(path).with(Tokens.agent(Tokens.OTHER_AGENT))).andExpect(status().isNotFound());
        mvc.perform(put(path).with(Tokens.agent(Tokens.OTHER_AGENT)).contentType(MediaType.APPLICATION_JSON)
                .content(update(created, "Taken", "[]", 0))).andExpect(status().isNotFound());
        mvc.perform(delete(path).with(Tokens.agent(Tokens.OTHER_AGENT))).andExpect(status().isNotFound());
        mvc.perform(window().with(Tokens.agent(Tokens.OTHER_AGENT)))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(path).with(Tokens.agent())).andExpect(jsonPath("$.title").value("Mine"));
    }

    @Test
    void anUpdateQueuesAnInviteUpdateWithAHigherSequence() throws Exception {
        JsonNode created = create(meeting("Review"));
        String path = "/api/engagement/events/" + created.get("id").asText();

        mvc.perform(put(path).with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                        .content(update(created, "Review, moved agenda", "[30]", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.invite.sequence").value(1))
                .andExpect(jsonPath("$.invite.status").value("PENDING"))
                .andExpect(jsonPath("$.reminders.length()").value(1))
                .andExpect(jsonPath("$.reminders[0].offsetMinutes").value(30));

        assertThat(jdbc.queryForList("SELECT kind, status FROM mail_queue WHERE kind <> 'REMINDER' ORDER BY ics_sequence"))
                .extracting(row -> row.get("kind") + ":" + row.get("status"))
                .containsExactly("INVITE:CANCELLED", "UPDATE:PENDING");

        mvc.perform(put(path).with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                        .content(update(created, "Stale", "[]", 0)))
                .andExpect(status().isConflict());
    }

    @Test
    void changingOnlyTheRemindersSendsNoNewInvite() throws Exception {
        JsonNode created = create(meeting("Review"));

        mvc.perform(put("/api/engagement/events/" + created.get("id").asText()).with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON).content(update(created, "Review", "[60]", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invite.sequence").value(0))
                .andExpect(jsonPath("$.reminders[0].offsetMinutes").value(60));
    }

    @Test
    void cancellingQueuesOneCancellationAndCancelsReminders() throws Exception {
        JsonNode created = create(meeting("Review"));
        String path = "/api/engagement/events/" + created.get("id").asText();

        mvc.perform(delete(path).with(Tokens.agent())).andExpect(status().isNoContent());
        mvc.perform(delete(path).with(Tokens.agent())).andExpect(status().isNoContent());

        mvc.perform(get(path).with(Tokens.agent()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.invite.sequence").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_queue WHERE kind = 'CANCEL'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM mail_queue WHERE kind = 'REMINDER' AND status = 'PENDING'", Integer.class))
                .isZero();
        mvc.perform(put(path).with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                .content(update(created, "Again", "[]", 1))).andExpect(status().isConflict());
        mvc.perform(window().with(Tokens.agent()))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(window().param("includeCancelled", "true").with(Tokens.agent()))
                .andExpect(jsonPath("$.length()").value(1));
        assertThat(audits("engagement.event-cancel")).hasSize(1);
    }

    @Test
    void withInvitesOffTheInviteIsDisabled() throws Exception {
        mvc.perform(put("/api/engagement/preferences").with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"invitesEnabled\":false,\"remindersEnabled\":true}")).andExpect(status().isOk());

        assertThat(create(meeting("Quiet")).at("/invite/status").asText()).isEqualTo("DISABLED");
    }

    @Test
    void listsTheWindowOfAtMost92Days() throws Exception {
        create(meeting("In window"));
        create(event("Later", "2026-12-01T10:00:00+08:00", "2026-12-01T11:00:00+08:00", "[]", null));

        mvc.perform(window().with(Tokens.agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("In window"));
        mvc.perform(get("/api/engagement/events").param("from", "2026-10-01T00:00:00Z").param("to", "2027-01-10T00:00:00Z")
                .with(Tokens.agent())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/engagement/events").param("from", "2026-10-01T00:00:00Z").with(Tokens.agent()))
                .andExpect(status().isBadRequest());
    }
}
