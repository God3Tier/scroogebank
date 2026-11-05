package bank.engagement.alertreports;

import bank.engagement.integration.clients.ClientNotFoundException;
import bank.engagement.integration.clients.ClientRecord;
import bank.engagement.integration.clients.ClientServiceUnavailableException;
import bank.engagement.integration.sources.ReportPackages.Package;
import bank.engagement.integration.sources.ReportPackages.Recipient;
import bank.engagement.integration.sources.ReportPackages.Source;
import bank.engagement.integration.sources.SourceNotFoundException;
import bank.engagement.integration.sources.StaleReportVersionException;
import bank.engagement.notifications.MailSendException;
import bank.engagement.notifications.OutgoingMail;
import bank.engagement.testsupport.PostgresIntegrationTest;
import bank.engagement.testsupport.Tokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Alert report delivery with the kill switch on, event-alert-service and client-service mocked (ADR-0084). */
@TestPropertySource(properties = "engagement.alert-reports.enabled=true")
class AlertReportApiTest extends PostgresIntegrationTest {

    private static final UUID EVENT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID REPORT = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID ALERT_A = UUID.fromString("30000000-0000-4000-8000-00000000000a");
    private static final UUID ALERT_B = UUID.fromString("30000000-0000-4000-8000-00000000000b");
    private static final UUID CLIENT_A = UUID.fromString("40000000-0000-4000-8000-00000000000a");
    private static final UUID CLIENT_B = UUID.fromString("40000000-0000-4000-8000-00000000000b");
    private static final String KEY = "batch-key-0001";

    @Autowired
    private ObjectMapper json;

    private Package report(int version, Recipient... recipients) throws Exception {
        JsonNode body = json.readTree("""
                {"headline":"Issuer reports <results>",
                 "executiveSummary":[{"text":"Revenue rose.","sourceRefs":["s1"]},{"text":"Margins held.","sourceRefs":["s1","s2"]}],
                 "keyFacts":[{"label":"Revenue","value":"$10 million","context":"Up on the year","sourceRefs":["s1"]}],
                 "whyItMatters":[{"text":"Demand may grow.","kind":"POTENTIAL_IMPLICATION","sourceRefs":[]}],
                 "marketContext":null,"whatToMonitor":["Next quarter","Guidance"],"uncertainty":"One source.","sourceRefs":["s1","s2"]}
                """);
        return new Package(EVENT, REPORT, version, "alert-client-report-v2", body, "a".repeat(64),
                List.of(new Source("s1", "First source", "https://a.example/1", "a.example",
                                Instant.parse("2026-11-01T02:00:00Z"), Instant.parse("2026-11-01T04:00:00Z"), true),
                        new Source("s2", "Second source", "https://b.example/2", "b.example", null,
                                Instant.parse("2026-11-01T05:00:00Z"), true)),
                List.of(recipients));
    }

    private static Recipient ok(UUID alert, UUID client) {
        return new Recipient(alert, "OK", client.toString(), "NASDAQ-MSFT", "Microsoft Corporation");
    }

    private static Recipient refused(UUID alert) {
        return new Recipient(alert, "NOT_AUTHORISED", null, null, null);
    }

    @BeforeEach
    void setUp() throws Exception {
        when(reportPackages.fetch(any(), anyInt(), anyList())).thenReturn(report(1, ok(ALERT_A, CLIENT_A), ok(ALERT_B, CLIENT_B)));
        when(clients.findOwned(CLIENT_A)).thenReturn(new ClientRecord(CLIENT_A, "Alex", "alex@client.example", "SG"));
        when(clients.findOwned(CLIENT_B)).thenReturn(new ClientRecord(CLIENT_B, "Bo", "bo@client.example", "SG"));
    }

    private ResultActions send(String key, String body) throws Exception {
        return mvc.perform(post("/api/engagement/alert-report-deliveries").with(Tokens.agent())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(int version, String note, UUID... alerts) {
        return """
                {"eventId":"%s","expectedReportVersion":%d,"alertIds":[%s],"agentNote":%s}
                """.formatted(EVENT, version,
                String.join(",", java.util.Arrays.stream(alerts).map(a -> "\"" + a + "\"").toList()),
                note == null ? "null" : "\"" + note + "\"");
    }

    @Test
    void eachChosenClientGetsAnIndividuallyAddressedEmailInHtmlAndText() throws Exception {
        send(KEY, body(1, "Happy to talk this through.", ALERT_A, ALERT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportVersion").value(1))
                .andExpect(jsonPath("$.recipients[0].status").value("SENT"))
                .andExpect(jsonPath("$.recipients[1].status").value("SENT"));

        assertThat(channel.sent()).hasSize(2);
        OutgoingMail first = channel.sent().get(0);
        OutgoingMail second = channel.sent().get(1);
        assertThat(first.to()).isEqualTo("alex@client.example");
        assertThat(second.to()).isEqualTo("bo@client.example");
        assertThat(first.bcc()).isEqualTo("synthetic.agent@example.test");
        assertThat(first.replyTo()).isEqualTo("synthetic.agent@example.test");
        assertThat(first.textBody()).startsWith("PORTFOLIO INTELLIGENCE / COMPANY EVENT")
                .contains("Dear Alex,", "Key facts and numbers", "Why it matters", "What to monitor", "Why you received this",
                        "Your portfolio has exposure to Microsoft Corporation, which may be affected by this event.",
                        "Margins held. [1] [2]", "Note from Synthetic Agent (not part of the verified report)",
                        "Happy to talk this through.", "Linked sources", "Published 1 Nov 2026 10:00 SGT; retrieved 1 Nov 2026 12:00 SGT",
                        "date not supplied", "This report is for information only.",
                        "Your relationship manager: Synthetic Agent, synthetic.agent@example.test")
                .doesNotContain("Market context", "Bo", "score", "confidence");
        assertThat(first.htmlBody()).contains("&lt;results&gt;").doesNotContain("<results>").contains("<a href=\"https://a.example/1\">");
        assertThat(second.textBody()).startsWith("PORTFOLIO INTELLIGENCE").contains("Dear Bo,").doesNotContain("Alex");
    }

    @Test
    void theSameRequestIsReplayedNotSentAgain() throws Exception {
        send(KEY, body(1, null, ALERT_A)).andExpect(status().isOk());
        send(KEY, body(1, null, ALERT_A)).andExpect(status().isOk()).andExpect(jsonPath("$.recipients[0].status").value("SENT"));

        assertThat(channel.sent()).hasSize(1);
        send(KEY, body(1, "A different note", ALERT_A)).andExpect(status().isConflict());
    }

    @Test
    void aNewBatchNeverResendsAReportAlreadyDeliveredToTheSameClient() throws Exception {
        send(KEY, body(1, null, ALERT_A)).andExpect(status().isOk());

        send("batch-key-0002", body(1, null, ALERT_A)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients[0].status").value("FAILED"))
                .andExpect(jsonPath("$.recipients[0].errorCode").value("ALREADY_DELIVERED"));

        assertThat(channel.sent()).hasSize(1);
    }

    @Test
    void aStaleReportVersionIsAConflictAndNothingIsStoredOrSent() throws Exception {
        when(reportPackages.fetch(any(), anyInt(), anyList())).thenThrow(new StaleReportVersionException());

        send(KEY, body(1, null, ALERT_A)).andExpect(status().isConflict());

        assertThat(channel.sent()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_report_batch", Integer.class)).isZero();
    }

    @Test
    void anEventThatIsNotTheAgentsIsNotFound() throws Exception {
        when(reportPackages.fetch(any(), anyInt(), anyList())).thenThrow(new SourceNotFoundException());

        send(KEY, body(1, null, ALERT_A)).andExpect(status().isNotFound());
    }

    @Test
    void aClientTheAlertServiceRefusesOrWhoWasReassignedFailsOnlyThatRecipient() throws Exception {
        when(reportPackages.fetch(any(), anyInt(), anyList())).thenReturn(report(1, refused(ALERT_A), ok(ALERT_B, CLIENT_B)));
        send(KEY, body(1, null, ALERT_A, ALERT_B)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients[0].status").value("NOT_AUTHORISED"))
                .andExpect(jsonPath("$.recipients[1].status").value("SENT"));

        when(reportPackages.fetch(any(), anyInt(), anyList())).thenReturn(report(1, ok(ALERT_A, CLIENT_A), ok(ALERT_B, CLIENT_B)));
        when(clients.findOwned(CLIENT_A)).thenThrow(new ClientNotFoundException());
        send("batch-key-0002", body(1, null, ALERT_A)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients[0].status").value("NOT_AUTHORISED"))
                .andExpect(jsonPath("$.recipients[0].errorCode").value("CLIENT_NOT_YOURS"));

        assertThat(channel.sent()).hasSize(1);
        assertThat(channel.sent().getFirst().to()).isEqualTo("bo@client.example");
    }

    @Test
    void aClientWithoutAnEmailAddressFailsAlone() throws Exception {
        when(clients.findOwned(CLIENT_A)).thenReturn(new ClientRecord(CLIENT_A, "Alex", " ", "SG"));

        send(KEY, body(1, null, ALERT_A, ALERT_B)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients[0].status").value("FAILED"))
                .andExpect(jsonPath("$.recipients[0].errorCode").value("MISSING_EMAIL"))
                .andExpect(jsonPath("$.recipients[1].status").value("SENT"));
    }

    @Test
    void aMailFailureThatMayPassStaysQueuedAndIsSentWhenTheRequestIsRepeated() throws Exception {
        channel.failWith(new MailSendException("Throttled", true));
        send(KEY, body(1, null, ALERT_A)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients[0].status").value("QUEUED"));
        assertThat(jdbc.queryForObject("SELECT snapshot IS NOT NULL FROM alert_report_batch", Boolean.class)).isTrue();

        channel.reset();
        send(KEY, body(1, null, ALERT_A)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients[0].status").value("SENT"));

        assertThat(channel.sent()).hasSize(1);
    }

    @Test
    void aClientServiceOutageStaysQueuedThenFailsAfterTheLastAttempt() throws Exception {
        when(clients.findOwned(CLIENT_A)).thenThrow(new ClientServiceUnavailableException("down"));

        for (int attempt = 1; attempt <= 2; attempt++) {
            send(KEY, body(1, null, ALERT_A)).andExpect(jsonPath("$.recipients[0].status").value("QUEUED"));
        }
        send(KEY, body(1, null, ALERT_A)).andExpect(jsonPath("$.recipients[0].status").value("FAILED"))
                .andExpect(jsonPath("$.recipients[0].errorCode").value("CLIENT_SERVICE_UNAVAILABLE"));
        assertThat(channel.sent()).isEmpty();
    }

    @Test
    void aPermanentMailFailureIsFailedAtOnce() throws Exception {
        channel.failWith(new MailSendException("MessageRejected", false));

        send(KEY, body(1, null, ALERT_A)).andExpect(jsonPath("$.recipients[0].status").value("FAILED"))
                .andExpect(jsonPath("$.recipients[0].errorCode").value("MessageRejected"));
    }

    @Test
    void storedContentIsClearedOnceNothingIsLeftToSendButHashesAndAuditStay() throws Exception {
        send(KEY, body(1, "Private note", ALERT_A)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT snapshot IS NULL AND agent_note IS NULL FROM alert_report_batch", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT length(trim(report_hash)) FROM alert_report_batch", Integer.class)).isEqualTo(64);
        assertThat(jdbc.queryForObject("SELECT note_hash IS NOT NULL FROM alert_report_batch", Boolean.class)).isTrue();
        assertThat(audits("engagement.alert-report-approved")).singleElement().satisfies(a -> assertThat(a)
                .contains("\"reportVersion\":1", "\"hasNote\":true").doesNotContain("Private note", "alex@client.example"));
        assertThat(audits("engagement.alert-report-queued")).hasSize(1);
        assertThat(audits("engagement.alert-report-sent")).singleElement().satisfies(a -> assertThat(a)
                .contains("\"status\":\"SENT\"").doesNotContain("alex@client.example"));
    }

    @Test
    void failedRecipientsAreAudited() throws Exception {
        when(clients.findOwned(CLIENT_A)).thenReturn(new ClientRecord(CLIENT_A, "Alex", null, "SG"));

        send(KEY, body(1, null, ALERT_A)).andExpect(status().isOk());

        assertThat(audits("engagement.alert-report-failed")).singleElement().satisfies(a -> assertThat(a)
                .contains("MISSING_EMAIL"));
    }

    @Test
    void theKillSwitchAndInputRulesApply() throws Exception {
        send("short", body(1, null, ALERT_A)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/engagement/alert-report-deliveries").with(Tokens.agent())
                .contentType(MediaType.APPLICATION_JSON).content(body(1, null, ALERT_A))).andExpect(status().isBadRequest());
        send(KEY, "{\"eventId\":\"" + EVENT + "\",\"expectedReportVersion\":1,\"alertIds\":[]}").andExpect(status().isBadRequest());
        UUID[] tooMany = new UUID[201];
        java.util.Arrays.setAll(tooMany, i -> UUID.randomUUID());
        send(KEY, body(1, null, tooMany)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/engagement/alert-report-deliveries").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body(1, null, ALERT_A))).andExpect(status().isUnauthorized());
    }
}
