package bank.engagement.notifications;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Keeps this task to one send per {@code minSendInterval}, the SES sandbox
 * rate (ADR-0053 decision 6). Shared by the dispatcher and draft sends.
 */
@Component
public class SendThrottle {

    private final long intervalNanos;
    private long lastSend;
    private boolean sentBefore;

    public SendThrottle(MailProperties properties) {
        this.intervalNanos = properties.minSendInterval().toNanos();
    }

    public synchronized void acquire() {
        if (sentBefore && intervalNanos > 0) {
            long wait = lastSend + intervalNanos - System.nanoTime();
            if (wait > 0) {
                try {
                    Thread.sleep(Duration.ofNanos(wait));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new MailSendException("interrupted", true);
                }
            }
        }
        lastSend = System.nanoTime();
        sentBefore = true;
    }
}
