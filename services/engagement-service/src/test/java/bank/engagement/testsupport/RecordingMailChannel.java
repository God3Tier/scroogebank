package bank.engagement.testsupport;

import bank.engagement.notifications.MailChannel;
import bank.engagement.notifications.MailSendException;
import bank.engagement.notifications.OutgoingMail;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Records what would have been sent, or fails on demand. */
public class RecordingMailChannel implements MailChannel {

    private final List<OutgoingMail> sent = new CopyOnWriteArrayList<>();
    private volatile Function<OutgoingMail, MailSendException> failure = mail -> null;
    private volatile long delayMillis;

    @Override
    public String send(OutgoingMail mail) {
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        MailSendException error = failure.apply(mail);
        if (error != null) {
            throw error;
        }
        sent.add(mail);
        return "test-" + UUID.randomUUID();
    }

    public List<OutgoingMail> sent() {
        return sent;
    }

    public void failWith(MailSendException error) {
        failure = mail -> error;
    }

    public void delay(long millis) {
        delayMillis = millis;
    }

    public void reset() {
        sent.clear();
        failure = mail -> null;
        delayMillis = 0;
    }
}
