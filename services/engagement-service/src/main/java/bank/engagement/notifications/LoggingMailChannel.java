package bank.engagement.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * For local runs without SES ({@code ENGAGEMENT_MAIL_PROVIDER=log}). Logs that
 * a message would have gone, never its addresses or text.
 */
public class LoggingMailChannel implements MailChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailChannel.class);

    @Override
    public String send(OutgoingMail mail) {
        String messageId = "local-" + UUID.randomUUID();
        log.info("Mail not sent (log provider): {} calendar={}", messageId, mail.calendar() != null);
        return messageId;
    }
}
