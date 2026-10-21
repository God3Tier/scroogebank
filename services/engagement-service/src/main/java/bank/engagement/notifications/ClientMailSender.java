package bank.engagement.notifications;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sends one approved client email straight away, in the agent's request
 * (ADR-0054 decision 6). It shares the send rate with the dispatcher.
 */
@Component
public class ClientMailSender {

    private final ObjectProvider<MailChannel> channels;
    private final SendThrottle throttle;

    public ClientMailSender(ObjectProvider<MailChannel> channels, SendThrottle throttle) {
        this.channels = channels;
        this.throttle = throttle;
    }

    /**
     * @throws MailUnavailableException no mail provider is configured
     * @throws MailSendException        the provider did not accept the message
     */
    public String send(OutgoingMail mail) {
        MailChannel channel = channels.getIfAvailable();
        if (channel == null) {
            throw new MailUnavailableException();
        }
        throttle.acquire();
        return channel.send(mail);
    }
}
