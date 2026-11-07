package bank.alert.api;

import bank.alert.report.ReportEdits;
import bank.alert.report.ReportReviewController;
import bank.alert.report.ReportReviewService;
import bank.alert.report.ReportReviewService.ApprovalView;
import bank.alert.report.ReportReviewService.CheckItem;
import bank.alert.report.ReportReviewService.SentOutItem;
import bank.alert.report.ReportReviewService.SentOutRow;
import bank.alert.report.ReportReviewService.SinglePdf;
import bank.alert.security.JwtRoleConverter;
import bank.alert.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The news-alert review routes: who may call them, and the shape of a refused edit. Checked against the contract. */
@WebMvcTest(ReportReviewController.class)
@Import({SecurityConfiguration.class, JwtRoleConverter.class, AlertApiExceptionHandler.class})
class ReportReviewControllerTest {

    private static final UUID EVENT = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID ALERT = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID APPROVAL = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final String SECTIONS = """
            {"expectedReportVersion":1,"sections":{"executiveSummary":["One.","Two."],"keyFacts":[],
             "whyItMatters":["Why."],"whatToMonitor":["A","B"],"uncertainty":"U."}}
            """;

    @Autowired MockMvc mockMvc;
    @MockBean ReportReviewService reviews;
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void onlyAgentsEditAndApprove() throws Exception {
        when(reviews.approve(EVENT, "agent-a", 1)).thenReturn(new ApprovalView(APPROVAL, EVENT, 1, false,
                Instant.parse("2026-11-07T01:00:00Z"), Instant.parse("2026-11-14T01:00:00Z"), true, false));

        mockMvc.perform(post("/api/alerts/events/{id}/report/approval", EVENT).with(user("agent-a").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedReportVersion\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        mockMvc.perform(post("/api/alerts/events/{id}/report/approval", EVENT).with(user("admin-1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedReportVersion\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/alerts/events/{id}/report/edit", EVENT).with(user("admin-1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(SECTIONS))
                .andExpect(status().isForbidden());
        verify(reviews, never()).approve(any(), eq("admin-1"), anyInt());
    }

    @Test
    void aRefusedEditNamesTheSectionAndItem() throws Exception {
        when(reviews.saveEdit(eq(EVENT), eq("agent-a"), eq(1), any())).thenThrow(new ReportEdits.EditRefused(
                new ReportEdits.Violation("executiveSummary", 0, "UNSUPPORTED_FIGURE", "The figure 42 is not in the sources")));

        mockMvc.perform(put("/api/alerts/events/{id}/report/edit", EVENT).with(user("agent-a").roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content(SECTIONS))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FIGURE"))
                .andExpect(jsonPath("$.section").value("executiveSummary"))
                .andExpect(jsonPath("$.index").value(0));
    }

    @Test
    void adminsReadTheReportPdfAndTheSentOutViewButNeverSendOut() throws Exception {
        when(reviews.singlePdf(EVENT, null, "admin-1", true)).thenReturn(new SinglePdf("Portfolio-update-2026-11-07.pdf",
                "%PDF-1.7".getBytes()));
        when(reviews.sentOutList("admin-1", true, 100)).thenReturn(List.of(new SentOutRow(ALERT, EVENT,
                "11111111-2222-4333-8444-555555555555", "AAPL", "AAPL", "Apple Inc", "Apple reports", 1, false,
                Instant.parse("2026-11-07T02:00:00Z"))));

        mockMvc.perform(get("/api/alerts/events/{id}/report/pdf", EVENT).with(user("admin-1").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mockMvc.perform(get("/api/alerts/sent-out").with(user("admin-1").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].issuer").value("Apple Inc"));
        mockMvc.perform(post("/api/alerts/sent-out").with(user("admin-1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertIds\":[\"" + ALERT + "\"],\"draftId\":\"" + UUID.randomUUID() + "\",\"sesMessageId\":\"m-1\"}"))
                .andExpect(status().isForbidden());
        verify(reviews, never()).sentOut(anyString(), any(), any(), any());
    }

    @Test
    void engagementServiceChecksAndRecordsWithTheAgentsToken() throws Exception {
        var agent = user("agent-a").roles("AGENT");
        when(reviews.sendCheck("agent-a", List.of(ALERT))).thenReturn(List.of(new CheckItem(ALERT, "OK", APPROVAL, "h".repeat(64))));
        when(reviews.sentOut(eq("agent-a"), eq(List.of(ALERT)), any(), eq("m-1")))
                .thenReturn(List.of(new SentOutItem(ALERT, "SENT_OUT")));

        mockMvc.perform(post("/api/alerts/send-check").with(agent).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertIds\":[\"" + ALERT + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].outcome").value("OK"));
        mockMvc.perform(post("/api/alerts/sent-out").with(agent).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertIds\":[\"" + ALERT + "\"],\"draftId\":\"" + UUID.randomUUID() + "\",\"sesMessageId\":\"m-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].outcome").value("SENT_OUT"));
        mockMvc.perform(post("/api/alerts/send-check").with(agent).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertIds\":[]}"))
                .andExpect(status().isBadRequest());
        verify(reviews, never()).sentOutList(anyString(), anyBoolean(), anyInt());
    }
}
