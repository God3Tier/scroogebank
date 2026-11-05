package bank.engagement.reports;

import bank.engagement.audit.AuditAction;
import bank.engagement.audit.AuditTrail;
import bank.engagement.integration.clients.ClientDirectory;
import bank.engagement.integration.clients.ClientRecord;
import bank.engagement.integration.identity.AgentDirectory;
import bank.engagement.integration.identity.AgentProfile;
import bank.engagement.integration.sources.ReportAlreadySentException;
import bank.engagement.integration.sources.ReportDeliveries;
import bank.engagement.integration.sources.ReportDeliveries.Delivery;
import bank.engagement.integration.sources.ReportDeliveries.Source;
import bank.engagement.integration.sources.SourceNotFoundException;
import bank.engagement.notifications.ClientMailSender;
import bank.engagement.notifications.MailProperties;
import bank.engagement.notifications.MailSendException;
import bank.engagement.notifications.OutgoingMail;
import bank.engagement.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportEmailServiceTest {

    private static final UUID REPORT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ALERT = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID CLIENT = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final String REPORT_JSON = """
            {"headline":"Issuer reports results",
             "executiveSummary":[{"text":"Revenue rose.","sourceRefs":["s1"]},{"text":"Margins held.","sourceRefs":["s1","s2"]}],
             "keyFacts":[{"label":"Revenue","value":"$10 million","context":"Up on the year","sourceRefs":["s1"]}],
             "whyItMatters":[{"text":"Demand may grow.","kind":"POTENTIAL_IMPLICATION","sourceRefs":[]}],
             "marketContext":null,"whatToMonitor":["Next quarter","Guidance"],"uncertainty":"One source.","sourceRefs":["s1","s2"]}
            """;

    private ReportDeliveries deliveries;
    private ClientDirectory clients;
    private AgentDirectory agents;
    private ClientMailSender sender;
    private AuditTrail audit;
    private ReportEmailService service;

    private Delivery delivery(String status, String clientRef) throws Exception {
        return new Delivery(REPORT, ALERT, clientRef, status, new ObjectMapper().readTree(REPORT_JSON),
                List.of(new Source("s1", "First source", "https://a.example/1", "a.example"),
                        new Source("s2", "Second source", "https://b.example/2", "b.example")),
                "Your portfolio has exposure to Issuer Ltd, which may be affected by this event.",
                "This report is for information only.");
    }

    @BeforeEach
    void setUp() throws Exception {
        deliveries = mock(ReportDeliveries.class);
        clients = mock(ClientDirectory.class);
        agents = mock(AgentDirectory.class);
        sender = mock(ClientMailSender.class);
        audit = mock(AuditTrail.class);
        MailProperties mail = mock(MailProperties.class);
        when(mail.fromAddress()).thenReturn("no-reply@bank.example");
        service = new ReportEmailService(deliveries, clients, agents, sender, mail, audit);
        when(deliveries.get(REPORT, ALERT)).thenReturn(delivery("APPROVED", CLIENT.toString()));
        when(clients.findOwned(CLIENT)).thenReturn(new ClientRecord(CLIENT, "Alex", "alex@client.example", "SG"));
        when(agents.find("agent-1")).thenReturn(Optional.of(new AgentProfile("agent-1", "rm@bank.example", "Sam Tan", true)));
        when(sender.send(any())).thenReturn("ses-1");
    }

    @Test
    void sendsOneIndividuallyAddressedEmailWithTheFixedTextAndContactDetails() {
        service.send("agent-1", REPORT, ALERT);

        ArgumentCaptor<OutgoingMail> mail = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(sender).send(mail.capture());
        OutgoingMail sent = mail.getValue();
        assertThat(sent.to()).isEqualTo("alex@client.example");
        assertThat(sent.bcc()).isEqualTo("rm@bank.example");
        assertThat(sent.subject()).isEqualTo("Issuer reports results");
        assertThat(sent.textBody()).startsWith("Dear Alex,")
                .contains("Margins held. [1] [2]", "Potential implication: Demand may grow.", "[1] First source - https://a.example/1")
                .contains("Your portfolio has exposure to Issuer Ltd, which may be affected by this event.")
                .contains("This report is for information only.")
                .contains("Your relationship manager: Sam Tan, rm@bank.example");
        verify(deliveries).claim(REPORT, ALERT);
        verify(deliveries).sent(REPORT, ALERT, "ses-1");
        verify(audit).record(eq(AuditAction.EMAIL_SEND), eq("agent-1"), anyMap());
    }

    @Test
    void aReportNotApprovedForTheClientIsNotFoundAndNothingIsSent() {
        when(deliveries.get(REPORT, ALERT)).thenThrow(new SourceNotFoundException());

        assertThatThrownBy(() -> service.send("agent-1", REPORT, ALERT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
        verify(sender, never()).send(any());
    }

    @Test
    void aReportAlreadySentOrBeingSentIsRefused() throws Exception {
        when(deliveries.get(REPORT, ALERT)).thenReturn(delivery("SENT", CLIENT.toString()));
        assertThatThrownBy(() -> service.send("agent-1", REPORT, ALERT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(409));

        when(deliveries.get(REPORT, ALERT)).thenReturn(delivery("APPROVED", CLIENT.toString()));
        org.mockito.Mockito.doThrow(new ReportAlreadySentException()).when(deliveries).claim(REPORT, ALERT);
        assertThatThrownBy(() -> service.send("agent-1", REPORT, ALERT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(409));
        verify(sender, never()).send(any());
    }

    @Test
    void anAlertWithoutAClientRecordOrAnAddressIsRefusedBeforeClaiming() throws Exception {
        when(deliveries.get(REPORT, ALERT)).thenReturn(delivery("APPROVED", "syn-client-001"));
        assertThatThrownBy(() -> service.send("agent-1", REPORT, ALERT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(422));

        when(deliveries.get(REPORT, ALERT)).thenReturn(delivery("APPROVED", CLIENT.toString()));
        when(clients.findOwned(CLIENT)).thenReturn(new ClientRecord(CLIENT, "Alex", " ", "SG"));
        assertThatThrownBy(() -> service.send("agent-1", REPORT, ALERT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(422));
        verify(deliveries, never()).claim(any(), any());
    }

    @Test
    void aMailFailureReleasesTheReportSoItCanBeTriedAgain() {
        when(sender.send(any())).thenThrow(new MailSendException("Throttled", true));

        assertThatThrownBy(() -> service.send("agent-1", REPORT, ALERT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(502));
        verify(deliveries).release(REPORT, ALERT);
        verify(deliveries, never()).sent(any(), any(), any());
        verify(audit).record(eq(AuditAction.EMAIL_SEND_FAILED), eq("agent-1"), anyMap());
    }
}
