package bank.engagement.drafting;

import bank.engagement.integration.clients.ClientRecord;
import bank.engagement.integration.sources.NewsReports;
import bank.engagement.integration.sources.SourceUnavailableException;
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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** News Alert drafts, with event-alert-service mocked (news-alert-drafts plan, sections 5.5 to 5.7). */
@TestPropertySource(properties = "engagement.news.max-reports-per-email=3")
class NewsDraftApiTest extends PostgresIntegrationTest {

    private static final UUID CLIENT = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID APPLE_EVENT = UUID.fromString("e0000000-0000-4000-8000-00000000000a");
    private static final UUID SHELL_EVENT = UUID.fromString("e0000000-0000-4000-8000-00000000000b");
    private static final UUID APPLE_ALERT = UUID.fromString("a0000000-0000-4000-8000-00000000000a");
    private static final UUID SHELL_ALERT = UUID.fromString("a0000000-0000-4000-8000-00000000000b");
    private static final UUID APPLE_APPROVAL = UUID.fromString("b0000000-0000-4000-8000-00000000000a");
    private static final UUID SHELL_APPROVAL = UUID.fromString("b0000000-0000-4000-8000-00000000000b");
    private static final String APPLE_HASH = "a".repeat(64);
    private static final String SHELL_HASH = "b".repeat(64);
    private static final byte[] PDF = "%PDF-1.7 synthetic".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        when(clients.findOwned(CLIENT)).thenReturn(new ClientRecord(CLIENT, "Synthetic", "synthetic.client@example.test", "Singapore"));
        when(newsReports.packageFor(eq(APPLE_EVENT), anyList())).thenReturn(List.of(
                item(APPLE_ALERT, APPLE_APPROVAL, "Apple Inc", "Apple reports record revenue", APPLE_HASH,
                        "Apple reported record revenue as services grew.")));
        when(newsReports.packageFor(eq(SHELL_EVENT), anyList())).thenReturn(List.of(
                item(SHELL_ALERT, SHELL_APPROVAL, "Shell plc", "Shell raises its dividend", SHELL_HASH, null)));
    }

    private static NewsReports.PackageItem item(UUID alert, UUID approval, String issuer, String headline, String hash,
            String sentence) {
        return new NewsReports.PackageItem(alert, "OK", approval, CLIENT.toString(), "INS", issuer, headline,
                Instant.parse("2026-10-20T01:00:00Z"), Instant.parse("2026-10-27T01:00:00Z"), false, hash,
                sentence == null ? headline : sentence);
    }

    private JsonNode notify(UUID eventId, UUID... alerts) throws Exception {
        MvcResult result = mvc.perform(post("/api/engagement/drafts/news").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("eventId", eventId, "alertIds", List.of(alerts)))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode draft(String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/engagement/drafts/" + id).with(Tokens.agent()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void pdfWith(String appleHash) {
        when(newsReports.combinedPdf(anyList(), any())).thenReturn(new NewsReports.Pdf("Portfolio-update-2026-10-21.pdf",
                LocalDate.of(2026, 10, 21), Base64.getEncoder().encodeToString(PDF), "p".repeat(64), 6, List.of(
                new NewsReports.Section(APPLE_APPROVAL, "Apple Inc", "Apple reports record revenue", 3, appleHash),
                new NewsReports.Section(SHELL_APPROVAL, "Shell plc", "Shell raises its dividend", 4, SHELL_HASH))));
    }

    private void checksPass() {
        when(newsReports.sendCheck(anyList())).thenReturn(List.of(
                new NewsReports.CheckItem(APPLE_ALERT, "OK", APPLE_APPROVAL, APPLE_HASH),
                new NewsReports.CheckItem(SHELL_ALERT, "OK", SHELL_APPROVAL, SHELL_HASH)));
    }

    @Test
    void notifyingPutsEachClientsReportsOnOneDraftWithASummaryAndNoModel() throws Exception {
        JsonNode first = notify(APPLE_EVENT, APPLE_ALERT);
        assertThat(first.at("/drafts/0/created").asBoolean()).isTrue();
        String id = first.at("/drafts/0/draftId").asText();

        JsonNode second = notify(SHELL_EVENT, SHELL_ALERT);
        assertThat(second.at("/drafts/0/draftId").asText()).as("the same client's open draft").isEqualTo(id);
        assertThat(second.at("/drafts/0/reportCount").asInt()).isEqualTo(2);

        JsonNode view = draft(id);
        assertThat(view.get("purpose").asText()).isEqualTo("NEWS_ALERT");
        assertThat(view.get("tone").asText()).isEqualTo("NONE");
        assertThat(view.get("source").asText()).isEqualTo("NEWS");
        assertThat(view.get("subject").asText()).isEqualTo("Your portfolio update: Apple Inc and Shell plc");
        assertThat(view.get("body").asText())
                .startsWith("Dear Synthetic,\n\nHere is an update on 2 companies in your portfolio.")
                .contains("• Apple Inc: Apple reported record revenue as services grew.")
                .contains("• Shell plc: Shell raises its dividend");
        assertThat(view.at("/news/reports")).hasSize(2);
        assertThat(view.at("/news/guide").asText()).contains("Apple Inc: Apple reports record revenue");
        assertThat(view.at("/news/fixedText").asText())
                .contains("Your portfolio has exposure to Apple Inc and Shell plc")
                .contains("is not investment advice")
                .contains("Your relationship manager: Synthetic Agent");

        JsonNode again = notify(APPLE_EVENT, APPLE_ALERT);
        assertThat(again.get("drafts")).isEmpty();
        assertThat(again.at("/skipped/0/reason").asText()).isEqualTo("ALREADY_ON_DRAFT");
        mvc.perform(get("/api/engagement/drafts/news/open-alerts").with(Tokens.agent()))
                .andExpect(jsonPath("$.alertIds.length()").value(2));
        assertThat(audits("engagement.draft-generate")).hasSize(2)
                .allSatisfy(audit -> assertThat(audit).contains("\"purpose\":\"NEWS_ALERT\"", "\"source\":\"NEWS\""));
    }

    @Test
    void aReportThatIsNotApprovedOrOverTheLimitIsSkipped() throws Exception {
        when(newsReports.packageFor(eq(APPLE_EVENT), anyList())).thenReturn(List.of(new NewsReports.PackageItem(
                APPLE_ALERT, "NOT_APPROVED", null, null, null, null, null, null, null, false, null, null)));
        JsonNode result = notify(APPLE_EVENT, APPLE_ALERT);
        assertThat(result.get("drafts")).isEmpty();
        assertThat(result.at("/skipped/0/reason").asText()).isEqualTo("NOT_APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_drafts", Integer.class)).isZero();

        UUID[] more = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        when(newsReports.packageFor(eq(SHELL_EVENT), anyList())).thenReturn(java.util.Arrays.stream(more)
                .map(alert -> item(alert, UUID.randomUUID(), "Issuer " + alert, "Headline", "c".repeat(64), null)).toList());
        JsonNode limited = notify(SHELL_EVENT, more);
        assertThat(limited.at("/drafts/0/reportCount").asInt()).isEqualTo(3);
        assertThat(limited.at("/skipped/0/reason").asText()).isEqualTo("LIMIT_REACHED");
    }

    @Test
    void theAgentsSummaryIsKeptWhenReportsChangeAndCanBeRebuilt() throws Exception {
        String id = notify(APPLE_EVENT, APPLE_ALERT).at("/drafts/0/draftId").asText();
        JsonNode view = draft(id);
        mvc.perform(put("/api/engagement/drafts/" + id).with(Tokens.agent()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("subject", view.get("subject").asText(),
                                "body", "Dear Synthetic,\n\nMy own words about Apple.", "version", view.get("version").asInt()))))
                .andExpect(status().isOk());

        notify(SHELL_EVENT, SHELL_ALERT);
        JsonNode changed = draft(id);
        assertThat(changed.get("body").asText()).isEqualTo("Dear Synthetic,\n\nMy own words about Apple.");
        assertThat(changed.at("/news/summaryEdited").asBoolean()).isTrue();
        assertThat(changed.at("/news/rebuildSuggested").asBoolean()).isTrue();
        assertThat(changed.get("subject").asText()).as("the untouched subject follows the reports")
                .isEqualTo("Your portfolio update: Apple Inc and Shell plc");

        mvc.perform(post("/api/engagement/drafts/" + id + "/summary/rebuild").with(Tokens.agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.news.rebuildSuggested").value(false))
                .andExpect(jsonPath("$.news.summaryEdited").value(false));
        assertThat(draft(id).get("body").asText()).contains("Here is an update on 2 companies");
    }

    @Test
    void removingTheLastReportDiscardsTheDraftAndReleasesTheReport() throws Exception {
        String id = notify(APPLE_EVENT, APPLE_ALERT).at("/drafts/0/draftId").asText();
        notify(SHELL_EVENT, SHELL_ALERT);

        mvc.perform(delete("/api/engagement/drafts/" + id + "/reports/" + SHELL_ALERT).with(Tokens.agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.news.reports.length()").value(1))
                .andExpect(jsonPath("$.subject").value("Your portfolio update: Apple Inc"));
        mvc.perform(delete("/api/engagement/drafts/" + id + "/reports/" + APPLE_ALERT).with(Tokens.agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCARDED"));
        assertThat(audits("engagement.draft-report-removed")).hasSize(2);

        assertThat(notify(APPLE_EVENT, APPLE_ALERT).at("/drafts/0/created").asBoolean())
                .as("a released report can go on a new draft").isTrue();
    }

    @Test
    void sendingChecksEveryReportAttachesOnePdfAndMovesTheClientsToSentOut() throws Exception {
        notify(APPLE_EVENT, APPLE_ALERT);
        String id = notify(SHELL_EVENT, SHELL_ALERT).at("/drafts/0/draftId").asText();
        checksPass();
        pdfWith(APPLE_HASH);
        when(newsReports.sentOut(anyList(), any(), anyString())).thenReturn(List.of(
                new NewsReports.SentOutItem(APPLE_ALERT, "SENT_OUT"), new NewsReports.SentOutItem(SHELL_ALERT, "SENT_OUT")));
        int version = draft(id).get("version").asInt();

        mvc.perform(post("/api/engagement/drafts/" + id + "/send").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));

        assertThat(channel.sent()).singleElement().satisfies(mail -> {
            assertThat(mail.to()).isEqualTo("synthetic.client@example.test");
            assertThat(mail.bcc()).isEqualTo(AGENT_PROFILE.email());
            assertThat(mail.replyTo()).isEqualTo(AGENT_PROFILE.email());
            assertThat(mail.attachments()).singleElement().satisfies(file -> {
                assertThat(file.fileName()).isEqualTo("Portfolio-update-2026-10-21.pdf");
                assertThat(file.contentType()).isEqualTo("application/pdf");
                assertThat(file.content()).isEqualTo(PDF);
            });
            assertThat(mail.textBody())
                    .contains("Where to find each report in the attached PDF (Portfolio-update-2026-10-21.pdf):")
                    .contains("• Apple Inc: Apple reports record revenue. Page 3")
                    .contains("• Shell plc: Shell raises its dividend. Page 4")
                    .contains("Your portfolio has exposure to Apple Inc and Shell plc")
                    .contains("Your relationship manager: Synthetic Agent, " + AGENT_PROFILE.email());
        });
        verify(newsReports).sentOut(eq(List.of(APPLE_ALERT, SHELL_ALERT)), eq(UUID.fromString(id)), anyString());
        JsonNode sent = draft(id);
        assertThat(sent.get("body").isNull()).as("the text is cleared once sent").isTrue();
        assertThat(sent.at("/news/sentOutSynced").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_draft_reports WHERE summary_line IS NOT NULL", Integer.class)).isZero();
        assertThat(audits("engagement.email-send")).singleElement().asString()
                .contains(APPLE_APPROVAL.toString(), APPLE_HASH, "\"pdfHash\":\"" + "p".repeat(64) + "\"");
    }

    @Test
    void aReportThatCanNoLongerBeSentStopsTheWholeEmail() throws Exception {
        notify(APPLE_EVENT, APPLE_ALERT);
        String id = notify(SHELL_EVENT, SHELL_ALERT).at("/drafts/0/draftId").asText();
        when(newsReports.sendCheck(anyList())).thenReturn(List.of(
                new NewsReports.CheckItem(APPLE_ALERT, "EXPIRED", null, null),
                new NewsReports.CheckItem(SHELL_ALERT, "OK", SHELL_APPROVAL, SHELL_HASH)));
        int version = draft(id).get("version").asInt();

        mvc.perform(post("/api/engagement/drafts/" + id + "/send").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Apple Inc (its approval expired")));
        assertThat(channel.sent()).isEmpty();
        verify(newsReports, never()).combinedPdf(anyList(), any());

        checksPass();
        pdfWith("d".repeat(64));
        mvc.perform(post("/api/engagement/drafts/" + id + "/send").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The approved reports changed; reopen the draft"));
        assertThat(channel.sent()).isEmpty();
        assertThat(draft(id).get("status").asText()).as("the draft is given back").isEqualTo("DRAFT");
    }

    @Test
    void aFailedSentOutUpdateLeavesTheEmailSentAndCanBeRetried() throws Exception {
        notify(APPLE_EVENT, APPLE_ALERT);
        String id = notify(SHELL_EVENT, SHELL_ALERT).at("/drafts/0/draftId").asText();
        checksPass();
        pdfWith(APPLE_HASH);
        when(newsReports.sentOut(anyList(), any(), anyString())).thenThrow(new SourceUnavailableException("down"));
        int version = draft(id).get("version").asInt();

        mvc.perform(post("/api/engagement/drafts/" + id + "/send").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.news.sentOutSynced").value(false));
        assertThat(channel.sent()).hasSize(1);

        org.mockito.Mockito.reset(newsReports);
        when(newsReports.sentOut(anyList(), any(), anyString())).thenReturn(List.of(
                new NewsReports.SentOutItem(APPLE_ALERT, "ALREADY_SENT_OUT"), new NewsReports.SentOutItem(SHELL_ALERT, "SENT_OUT")));
        mvc.perform(post("/api/engagement/drafts/" + id + "/news-status").with(Tokens.agent()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.news.sentOutSynced").value(true));
        assertThat(channel.sent()).as("never sent twice").hasSize(1);
    }

    @Test
    void thePreviewIsThePdfTheClientGetsAndDiscardingReleasesTheReports() throws Exception {
        String id = notify(APPLE_EVENT, APPLE_ALERT).at("/drafts/0/draftId").asText();
        pdfWith(APPLE_HASH);

        mvc.perform(get("/api/engagement/drafts/" + id + "/pdf").with(Tokens.agent()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PDF));
        verify(newsReports).combinedPdf(List.of(APPLE_APPROVAL), "Synthetic Agent");

        mvc.perform(delete("/api/engagement/drafts/" + id).with(Tokens.agent())).andExpect(status().isNoContent());
        mvc.perform(get("/api/engagement/drafts/news/open-alerts").with(Tokens.agent()))
                .andExpect(jsonPath("$.alertIds.length()").value(0));
        assertThat(notify(APPLE_EVENT, APPLE_ALERT).at("/drafts/0/created").asBoolean()).isTrue();
    }

    @Test
    void twoNotifyClicksAtOnceForOneClientMakeOneDraft() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return notify(APPLE_EVENT, APPLE_ALERT); });
            var second = pool.submit(() -> { start.await(); return notify(SHELL_EVENT, SHELL_ALERT); });
            start.countDown();
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_drafts WHERE purpose = 'NEWS_ALERT'", Integer.class))
                .as("one open draft for the client").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_draft_reports WHERE active", Integer.class))
                .as("each report on it once").isEqualTo(2);
    }

    @Test
    void theDatabaseRefusesASecondOpenNewsDraftForOneClient() {
        String insert = """
                INSERT INTO email_drafts (id, owner_agent_sub, client_id, purpose, tone, subject_template, body_template,
                    edited, source, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'NEWS_ALERT', 'NONE', 's', 'b', false, 'NEWS', ?, 0, now(), now())
                """;
        jdbc.update(insert, UUID.randomUUID(), Tokens.AGENT, CLIENT, "DRAFT");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> jdbc.update(insert, UUID.randomUUID(), Tokens.AGENT, CLIENT, "SENDING")))
                .as("a draft being sent counts as open").isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        jdbc.update("UPDATE email_drafts SET status = 'SENT', subject_template = NULL, body_template = NULL");
        jdbc.update(insert, UUID.randomUUID(), Tokens.AGENT, CLIENT, "DRAFT");
    }

    @Test
    void aClientWhoseDraftIsBeingSentIsSkippedAndNamedAsSuch() throws Exception {
        String id = notify(APPLE_EVENT, APPLE_ALERT).at("/drafts/0/draftId").asText();
        jdbc.update("UPDATE email_drafts SET status = 'SENDING' WHERE id = ?::uuid", id);

        JsonNode result = notify(SHELL_EVENT, SHELL_ALERT);

        assertThat(result.get("drafts")).isEmpty();
        assertThat(result.at("/skipped/0/reason").asText()).isEqualTo("DRAFT_BEING_SENT");
    }

    @Test
    void aReportRefusedForGoodAfterTheSendIsListedAndEndsTheRetry() throws Exception {
        notify(APPLE_EVENT, APPLE_ALERT);
        String id = notify(SHELL_EVENT, SHELL_ALERT).at("/drafts/0/draftId").asText();
        checksPass();
        pdfWith(APPLE_HASH);
        when(newsReports.sentOut(anyList(), any(), anyString())).thenReturn(List.of(
                new NewsReports.SentOutItem(APPLE_ALERT, "NOT_AUTHORISED"), new NewsReports.SentOutItem(SHELL_ALERT, "SENT_OUT")));
        int version = draft(id).get("version").asInt();

        mvc.perform(post("/api/engagement/drafts/" + id + "/send").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.news.sentOutSynced").value(true))
                .andExpect(jsonPath("$.news.unmarkedReports.length()").value(1))
                .andExpect(jsonPath("$.news.unmarkedReports[0].issuer").value("Apple Inc"))
                .andExpect(jsonPath("$.news.unmarkedReports[0].reason").value("NOT_AUTHORISED"));
        assertThat(draft(id).at("/news/unmarkedReports/0/alertId").asText()).isEqualTo(APPLE_ALERT.toString());
    }

    @Test
    void aReportTheSentOutAnswerLeavesOutKeepsTheRetry() throws Exception {
        notify(APPLE_EVENT, APPLE_ALERT);
        String id = notify(SHELL_EVENT, SHELL_ALERT).at("/drafts/0/draftId").asText();
        checksPass();
        pdfWith(APPLE_HASH);
        when(newsReports.sentOut(anyList(), any(), anyString())).thenReturn(List.of(new NewsReports.SentOutItem(SHELL_ALERT, "SENT_OUT")));
        int version = draft(id).get("version").asInt();

        mvc.perform(post("/api/engagement/drafts/" + id + "/send").with(Tokens.agent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.news.sentOutSynced").value(false));
    }

    @Test
    void theMimeMessageCarriesThePdf() {
        byte[] raw = bank.engagement.notifications.MimeMessageFactory.build(new OutgoingMail("Agent via Scrooge Bank",
                "no-reply@mail.example.test", "client@example.test", "agent@example.test", "agent@example.test",
                "Your portfolio update", "Text", "<p>Text</p>", null,
                List.of(new OutgoingMail.Attachment("Portfolio-update-2026-10-21.pdf", "application/pdf", PDF))),
                java.util.Date.from(Instant.parse("2026-10-21T02:00:00Z")));
        String message = new String(raw, StandardCharsets.US_ASCII);
        assertThat(message).contains("multipart/mixed", "application/pdf", "Portfolio-update-2026-10-21.pdf")
                .doesNotContain("Bcc:");
    }
}
