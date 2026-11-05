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
import bank.engagement.integration.sources.SourceNotFoundException;
import bank.engagement.integration.sources.SourceUnavailableException;
import bank.engagement.notifications.ClientMailSender;
import bank.engagement.notifications.HtmlText;
import bank.engagement.notifications.MailProperties;
import bank.engagement.notifications.MailSendException;
import bank.engagement.notifications.MailUnavailableException;
import bank.engagement.notifications.OutgoingMail;
import bank.engagement.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Emails an approved client report to one client (ADR-0084). event-alert-service holds the approval: it releases
 * the report only to the agent who approved it for this client, and lets one request claim the send, so a report
 * goes to a client at most once. The client address and name come from client-service with the agent token for
 * this message only and are never stored (ADR-0053 decision 5). The agent gets a Bcc copy, as with drafts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportEmailService {

    private final ReportDeliveries deliveries;
    private final ClientDirectory clients;
    private final AgentDirectory agents;
    private final ClientMailSender sender;
    private final MailProperties mail;
    private final AuditTrail audit;

    public void send(String owner, UUID reportId, UUID alertId) {
        Delivery delivery;
        try {
            delivery = deliveries.get(reportId, alertId);
        } catch (SourceNotFoundException ex) {
            throw ApiException.notFound("This report was not approved for that client");
        } catch (SourceUnavailableException ex) {
            throw ApiException.unavailable("Alerts are unavailable; nothing was sent");
        }
        if ("SENT".equals(delivery.status())) {
            throw ApiException.conflict("This report was already sent to that client");
        }
        UUID clientId;
        try {
            clientId = UUID.fromString(delivery.clientRef().strip());
        } catch (IllegalArgumentException ex) {
            throw ApiException.unprocessable("That alert is not linked to a client record");
        }
        ClientRecord client = clients.findOwned(clientId);
        AgentProfile agent = agents.find(owner)
                .filter(profile -> profile.enabled() && profile.hasEmail())
                .orElseThrow(() -> ApiException.unavailable("Your email address could not be looked up; nothing was sent"));
        if (client.email() == null || client.email().isBlank()) {
            throw ApiException.unprocessable("The client has no email address");
        }
        ReportEmailRenderer.Rendered email = ReportEmailRenderer.render(delivery, client.firstName(),
                agent.displayName(), agent.email());

        try {
            deliveries.claim(reportId, alertId);
        } catch (ReportAlreadySentException ex) {
            throw ApiException.conflict("This report was already sent or is being sent");
        } catch (SourceNotFoundException ex) {
            throw ApiException.notFound("This report was not approved for that client");
        } catch (SourceUnavailableException ex) {
            throw ApiException.unavailable("Alerts are unavailable; nothing was sent");
        }

        String messageId;
        try {
            messageId = sender.send(new OutgoingMail(agent.displayName() + " via Scrooge Bank", mail.fromAddress(),
                    client.email(), agent.email(), agent.email(), email.subject(), email.body(),
                    HtmlText.of(email.body()), null));
        } catch (MailUnavailableException ex) {
            release(reportId, alertId);
            throw ApiException.unavailable("Email sending is not configured; nothing was sent");
        } catch (MailSendException ex) {
            log.warn("Report {} not sent to alert {}: {}", reportId, alertId, ex.code());
            release(reportId, alertId);
            Map<String, Object> data = data(reportId, alertId, clientId);
            data.put("errorCode", ex.code());
            audit.record(AuditAction.EMAIL_SEND_FAILED, owner, data);
            throw ApiException.badGateway("The email service did not accept the message; the report was not sent");
        }

        Map<String, Object> data = data(reportId, alertId, clientId);
        data.put("sesMessageId", messageId);
        data.put("kind", "CLIENT_REPORT");
        audit.record(AuditAction.EMAIL_SEND, owner, data);
        try {
            deliveries.sent(reportId, alertId, messageId);
        } catch (RuntimeException ex) {
            // The mail is out; the claim stays so the report is not sent again. An agent can see it as sending.
            log.warn("Report {} sent for alert {} but the sent mark failed", reportId, alertId);
        }
    }

    private void release(UUID reportId, UUID alertId) {
        try {
            deliveries.release(reportId, alertId);
        } catch (RuntimeException ex) {
            log.warn("Report {} could not be released for alert {}", reportId, alertId);
        }
    }

    private static Map<String, Object> data(UUID reportId, UUID alertId, UUID clientId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportId", reportId.toString());
        data.put("alertId", alertId.toString());
        data.put("clientId", clientId.toString());
        return data;
    }
}
