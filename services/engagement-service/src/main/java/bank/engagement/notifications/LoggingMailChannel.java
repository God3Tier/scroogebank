package bank.engagement.notifications;


import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * For local runs without SES ({@code ENGAGEMENT_MAIL_PROVIDER=log}). Logs that
 * a message would have gone, never its addresses or text.
 */
@Slf4j
public class LoggingMailChannel implements MailChannel {

    @Override
    public String send(OutgoingMail mail) {
        String messageId = "local-" + UUID.randomUUID();
        log.info("Mail not sent (log provider): {} calendar={} attachments={} bcc={}", messageId, mail.calendar() != null,
                mail.attachments().size(),
                mail.bcc() != null);
        return messageId;
    }
}
