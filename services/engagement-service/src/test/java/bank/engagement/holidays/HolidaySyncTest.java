package bank.engagement.holidays;

import bank.engagement.testsupport.PostgresIntegrationTest;
import bank.engagement.testsupport.Tokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The sync against the seeded table, with the source replaced (ADR-0057). */
class HolidaySyncTest extends PostgresIntegrationTest {

    private static final String SOURCE = "https://data.gov.sg/datasets/d_test/view";

    @Autowired
    private HolidayService holidays;

    private final StubSource source = new StubSource();

    private HolidaySync sync() {
        return new HolidaySync(source, holidays, jdbc, clock);
    }

    @AfterEach
    void removeSyncedRows() {
        jdbc.update("DELETE FROM holidays WHERE source = ?", SOURCE);
    }

    @Test
    void addsANewlyPublishedYearOnceAndLeavesTheSeededRowsAlone() throws Exception {
        source.holidays.addAll(List.of(
                // Already seeded, "(Observed)" included, so nothing to add.
                new OfficialHoliday(LocalDate.of(2027, 2, 6), "Chinese New Year", false),
                new OfficialHoliday(LocalDate.of(2027, 2, 8), "Chinese New Year", true),
                new OfficialHoliday(LocalDate.of(2028, 1, 1), "New Year's Day", false),
                new OfficialHoliday(LocalDate.of(2028, 1, 26), "Chinese New Year", false),
                new OfficialHoliday(LocalDate.of(2028, 12, 25), "Christmas Day", false)));
        mvc.perform(get("/api/engagement/holidays/coverage?country=SG").with(Tokens.agent()))
                .andExpect(jsonPath("$.coveredThrough").value("2027-12-31"));

        HolidaySync.Result result = sync().sync();

        assertThat(result.fetched()).isTrue();
        assertThat(result.added()).isEqualTo(3);
        assertThat(result.coveredThrough()).isEqualTo(LocalDate.of(2028, 12, 31));
        assertThat(sync().sync().added()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holidays WHERE holiday_date = DATE '2027-02-08'",
                Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/engagement/holidays/coverage?country=SG").with(Tokens.agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("SG"))
                .andExpect(jsonPath("$.coveredThrough").value("2028-12-31"));
        mvc.perform(get("/api/engagement/holidays?country=SG&from=2028-12-01&to=2028-12-31").with(Tokens.agent()))
                .andExpect(jsonPath("$[0].name").value("Christmas Day"));
    }

    @Test
    void aFailedFetchChangesNothingAndReportsMissingYears() {
        source.fail = true;
        clock.set(Instant.parse("2027-10-05T02:00:00Z"));

        HolidaySync.Result result = sync().sync();

        assertThat(result.fetched()).isFalse();
        assertThat(result.added()).isZero();
        assertThat(result.coveredThrough()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(result.coverageShort()).as("2028 is expected by October 2027").isTrue();

        clock.set(Instant.parse("2027-06-01T02:00:00Z"));
        assertThat(sync().sync().coverageShort()).as("not yet expected in June").isFalse();
    }

    @Test
    void coverageIsAgentOnlyAndEmptyForACountryWithoutHolidays() throws Exception {
        mvc.perform(get("/api/engagement/holidays/coverage?country=MY").with(Tokens.agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("MY"))
                .andExpect(jsonPath("$.coveredThrough").doesNotExist());
        mvc.perform(get("/api/engagement/holidays/coverage?country=sg").with(Tokens.agent()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/engagement/holidays/coverage?country=SG").with(Tokens.admin()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/engagement/holidays/coverage?country=SG"))
                .andExpect(status().isUnauthorized());
    }

    private static final class StubSource implements HolidaySource {

        final List<OfficialHoliday> holidays = new ArrayList<>();
        boolean fail;

        @Override
        public String countryCode() {
            return "SG";
        }

        @Override
        public String sourceUrl() {
            return SOURCE;
        }

        @Override
        public List<OfficialHoliday> fetch() {
            if (fail) {
                throw new HolidaySourceException("down");
            }
            return holidays;
        }
    }
}
