package bank.engagement.notifications;

/** Where messages go: SES in AWS, the log locally. */
public interface MailChannel {

    /**
     * @return the provider's message ID, the only trace of the message kept
     * @throws MailSendException the message was not accepted
     */
    String send(OutgoingMail mail);
}
