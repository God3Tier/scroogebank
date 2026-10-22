package bank.engagement.notifications;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Sends due mail every minute in every task (ADR-0051 decision 4). Rows are
 * leased, so two tasks never send the same row at once; a task that dies
 * after SES accepted a message leaves a lease that expires, and the row is
 * sent again. Delivery is at least once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MailDispatcher {

    /** Logged when due mail has waited longer than this; a CloudWatch metric filter alarms on it. */
    static final Duration LAG_ALARM = Duration.ofMinutes(5);

    private final MailDispatchService rows;
    private final ObjectProvider<MailChannel> channels;
    private final ObjectProvider<MailComposer> composers;
    private final SendThrottle throttle;
    private final MailProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${engagement.mail.dispatcher.interval:60s}",
            initialDelayString = "${engagement.mail.dispatcher.interval:60s}")
    public void run() {
        try {
            dispatchDue();
            Duration lag = rows.lag(clock.instant());
            if (lag.compareTo(LAG_ALARM) > 0) {
                log.warn("mail_dispatch_lagging: oldest due mail has waited {} s", lag.toSeconds());
            }
        } catch (RuntimeException ex) {
            log.warn("Mail dispatch run failed: {}", ex.getClass().getSimpleName());
        }
    }

    /** @return how many rows this run claimed */
    public int dispatchDue() {
        MailChannel channel = channels.getIfAvailable();
        MailComposer composer = composers.getIfAvailable();
        if (channel == null || composer == null) {
            return 0;
        }
        MailProperties.Dispatcher settings = properties.dispatcher();
        List<QueuedMail> claimed = rows.claim(clock.instant(), settings.batchSize(), settings.lease());
        for (QueuedMail mail : claimed) {
            dispatch(mail, channel, composer, settings);
        }
        if (!claimed.isEmpty()) {
            log.info("Mail dispatch: {} rows", claimed.size());
        }
        return claimed.size();
    }

    private void dispatch(QueuedMail mail, MailChannel channel, MailComposer composer,
            MailProperties.Dispatcher settings) {
        try {
            switch (composer.compose(mail, clock.instant())) {
                case Composition.Skip skip -> rows.skipped(mail.id(), skip.status(), clock.instant());
                case Composition.Fail fail -> rows.failed(mail.id(), fail.errorCode(), clock.instant());
                case Composition.Ready ready -> {
                    throttle.acquire();
                    String messageId = channel.send(ready.mail());
                    rows.sent(mail.id(), messageId, clock.instant());
                }
            }
        } catch (MailSendException ex) {
            log.warn("Mail {} not sent: {}", mail.id(), ex.code());
            if (ex.retryable()) {
                rows.retry(mail.id(), ex.code(), settings.retryDelays(), clock.instant());
            } else {
                rows.failed(mail.id(), ex.code(), clock.instant());
            }
        } catch (RuntimeException ex) {
            log.warn("Mail {} not sent: {}", mail.id(), ex.getClass().getSimpleName());
            rows.retry(mail.id(), "dispatch_error", settings.retryDelays(), clock.instant());
        }
    }
}
