package bank.engagement.alertreports;

import bank.engagement.testsupport.PostgresIntegrationTest;
import bank.engagement.testsupport.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With the kill switch turned off (it is on by default, ADR-0084), nothing is queued or sent. */
@TestPropertySource(properties = "engagement.alert-reports.enabled=false")
class AlertReportDisabledTest extends PostgresIntegrationTest {

    @Test
    void withTheSwitchOffNothingIsQueuedOrSent() throws Exception {
        mvc.perform(post("/api/engagement/alert-report-deliveries").with(Tokens.agent()).header("Idempotency-Key", "batch-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"10000000-0000-4000-8000-000000000001\",\"expectedReportVersion\":1,"
                                + "\"alertIds\":[\"30000000-0000-4000-8000-00000000000a\"]}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(channel.sent()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_report_batch", Integer.class)).isZero();
    }
}
