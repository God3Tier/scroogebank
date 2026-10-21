package bank.engagement.notifications;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.AccountSuspendedException;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.LimitExceededException;
import software.amazon.awssdk.services.sesv2.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendingPausedException;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

import java.time.Clock;
import java.util.Date;

/**
 * Sends raw MIME through SES v2 {@code SendEmail} with the configuration set
 * (ADR-0053 decision 6). Only the returned message ID is kept.
 */
public class SesMailChannel implements MailChannel {

    private final SesV2Client ses;
    private final MailProperties properties;
    private final Clock clock;

    public SesMailChannel(SesV2Client ses, MailProperties properties, Clock clock) {
        this.ses = ses;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String send(OutgoingMail mail) {
        byte[] raw = MimeMessageFactory.build(mail, Date.from(clock.instant()));
        SendEmailRequest.Builder request = SendEmailRequest.builder()
                .content(content -> content.raw(message -> message.data(SdkBytes.fromByteArray(raw))))
                .overrideConfiguration(config -> config.apiCallTimeout(properties.sendTimeout()));
        if (properties.configurationSet() != null && !properties.configurationSet().isBlank()) {
            request.configurationSetName(properties.configurationSet());
        }
        try {
            return ses.sendEmail(request.build()).messageId();
        } catch (TooManyRequestsException | LimitExceededException ex) {
            throw new MailSendException("ses_throttled", true);
        } catch (SendingPausedException | AccountSuspendedException ex) {
            throw new MailSendException("ses_paused", true);
        } catch (MessageRejectedException | MailFromDomainNotVerifiedException | BadRequestException
                 | NotFoundException ex) {
            throw new MailSendException("ses_rejected", false);
        } catch (SdkException ex) {
            throw new MailSendException("ses_error", true);
        }
    }
}
