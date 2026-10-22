package bank.engagement.holidays;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DataGovSgHolidaySourceTest {

    private static final String PAGE = "https://data.gov.test/api/action/datastore_search?resource_id=d_test&limit=100&offset=";

    private MockRestServiceServer server;
    private DataGovSgHolidaySource source;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        source = new DataGovSgHolidaySource(builder.build(), new ObjectMapper(),
                new HolidaySyncProperties(true, "https://data.gov.test", "d_test", Duration.ofSeconds(5)));
    }

    private static String page(int total, String records) {
        return """
                {"success":true,"result":{"resource_id":"d_test","records":[%s],"total":%d,"limit":100}}
                """.formatted(records, total);
    }

    private static String record(String date, String holiday) {
        return "{\"date\":\"%s\",\"day\":\"Monday\",\"holiday\":\"%s\"}".formatted(date, holiday);
    }

    /** data.gov.sg's own shape, curly apostrophe and "(Observed)" rows included. */
    @Test
    void readsEveryPageAndNormalisesTheNames() {
        String first = IntStream.range(0, 100)
                .mapToObj(day -> record(LocalDate.of(2028, 1, 1).plusDays(day).toString(), "Day " + day))
                .collect(Collectors.joining(","));
        String second = String.join(",",
                record("2028-12-25", "Christmas Day"),
                record("2028-12-26", "Christmas Day (Observed)"),
                record("2029-01-01", "New Year’s Day"),
                record("not-a-date", "Broken"),
                record("2029-02-01", "<script>"));
        server.expect(requestTo(PAGE + 0)).andRespond(withSuccess(page(105, first), MediaType.APPLICATION_JSON));
        server.expect(requestTo(PAGE + 100)).andRespond(withSuccess(page(105, second), MediaType.APPLICATION_JSON));

        List<OfficialHoliday> holidays = source.fetch();

        server.verify();
        assertThat(holidays).hasSize(103);
        assertThat(holidays.subList(100, 103)).containsExactly(
                new OfficialHoliday(LocalDate.of(2028, 12, 25), "Christmas Day", false),
                new OfficialHoliday(LocalDate.of(2028, 12, 26), "Christmas Day", true),
                new OfficialHoliday(LocalDate.of(2029, 1, 1), "New Year's Day", false));
        assertThat(source.sourceUrl()).isEqualTo("https://data.gov.sg/datasets/d_test/view");
    }

    @Test
    void anErrorOrAnUnexpectedAnswerIsASourceFailure() {
        server.expect(requestTo(PAGE + 0)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(source::fetch).isInstanceOf(HolidaySourceException.class);

        server.reset();
        server.expect(requestTo(PAGE + 0)).andRespond(withSuccess("{\"success\":false}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(source::fetch).isInstanceOf(HolidaySourceException.class);

        server.reset();
        server.expect(requestTo(PAGE + 0)).andRespond(withSuccess(page(5_000, ""), MediaType.APPLICATION_JSON));
        assertThatThrownBy(source::fetch).isInstanceOf(HolidaySourceException.class);

        server.reset();
        server.expect(requestTo(PAGE + 0)).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));
        assertThatThrownBy(source::fetch).isInstanceOf(HolidaySourceException.class);
    }
}
