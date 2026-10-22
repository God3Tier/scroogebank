package bank.engagement.notifications;

import bank.engagement.calendar.EventInput;
import bank.engagement.calendar.EventService;
import bank.engagement.testsupport.PostgresIntegrationTest;
import bank.engagement.testsupport.TestBeans;
import bank.engagement.testsupport.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PreferencesAndHolidaysApiTest extends PostgresIntegrationTest {

    @Autowired
    private EventService events;

    @Test
    void preferencesDefaultToOn() throws Exception {
        mvc.perform(get("/api/engagement/preferences").with(Tokens.agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitesEnabled").value(true))
                .andExpect(jsonPath("$.remindersEnabled").value(true));
    }

    @Test
    void turningRemindersOffCancelsPendingReminders() throws Exception {
        events.create(Tokens.AGENT, new EventInput("Review", null, null,
                TestBeans.NOW.plus(Duration.ofDays(2)).atOffset(ZoneOffset.UTC),
                TestBeans.NOW.plus(Duration.ofDays(2).plusHours(1)).atOffset(ZoneOffset.UTC), null, List.of(60, 1440)));

        mvc.perform(put("/api/engagement/preferences").with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invitesEnabled\":true,\"remindersEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersEnabled").value(false));

        assertThat(jdbc.queryForList("SELECT status FROM mail_queue WHERE kind = 'REMINDER'", String.class))
                .containsOnly("CANCELLED").hasSize(2);
        assertThat(audits("engagement.preferences-update").getFirst())
                .contains("remindersEnabled").contains("\"remindersCancelled\":2");
        mvc.perform(get("/api/engagement/preferences").with(Tokens.agent()))
                .andExpect(jsonPath("$.remindersEnabled").value(false));
        mvc.perform(get("/api/engagement/preferences").with(Tokens.agent(Tokens.OTHER_AGENT)))
                .andExpect(jsonPath("$.remindersEnabled").value(true));
    }

    @Test
    void preferencesNeedBothFields() throws Exception {
        mvc.perform(put("/api/engagement/preferences").with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"invitesEnabled\":true}")).andExpect(status().isBadRequest());
    }

    @Test
    void listsSingaporeHolidaysWithObservedDays() throws Exception {
        mvc.perform(get("/api/engagement/holidays?country=SG&from=2026-11-01&to=2026-11-30").with(Tokens.agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Deepavali"))
                .andExpect(jsonPath("$[0].date").value("2026-11-08"))
                .andExpect(jsonPath("$[0].observed").value(false))
                .andExpect(jsonPath("$[1].date").value("2026-11-09"))
                .andExpect(jsonPath("$[1].observed").value(true));
        mvc.perform(get("/api/engagement/holidays?country=MY&from=2026-11-01&to=2026-11-30").with(Tokens.agent()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsBadHolidayQueries() throws Exception {
        mvc.perform(get("/api/engagement/holidays?country=sg&from=2026-11-01&to=2026-11-30").with(Tokens.agent()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/engagement/holidays?country=SG&from=2026-01-01&to=2027-01-03").with(Tokens.agent()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/engagement/holidays?country=SG&from=2026-11-30&to=2026-11-01").with(Tokens.agent()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/engagement/holidays?country=SG").with(Tokens.agent()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSeedHoldsBothYearsFromMom() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holidays WHERE country_code = 'SG'", Integer.class))
                .isEqualTo(26);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holidays WHERE observed", Integer.class)).isEqualTo(4);
    }
}
