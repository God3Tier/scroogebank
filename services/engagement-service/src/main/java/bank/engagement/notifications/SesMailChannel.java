package bank.engagement.notifications;

import jakarta.mail.internet.InternetAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
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

import java.io.UnsupportedEncodingException;
import java.time.Clock;
import java.util.Date;
import java.util.regex.Pattern;

/**
 * Sends raw MIME through SES v2 {@code SendEmail} with the configuration set
 * (ADR-0053 decision 6). Only the returned message ID is kept.
 */
@RequiredArgsConstructor
@Slf4j
public class SesMailChannel implements MailChannel {

    /** Anything shaped like an address in an SES error message, which can name the sender or a recipient. */
    private static final Pattern ADDRESS = Pattern.compile("[^\\s<>`'\"(),;:/\\[\\]]+@[^\\s<>`'\"(),;:/\\[\\]]+");

    private final SesV2Client ses;
    private final MailProperties properties;
    private final Clock clock;

    @Override
    public String send(OutgoingMail mail) {
        byte[] raw = MimeMessageFactory.build(mail, Date.from(clock.instant()));
        // FromEmailAddress names the sender explicitly, so the task role's
        // ses:FromAddress condition is checked against it (ADR-0053 decision 9).
        // SES writes it over the raw message's From header, so it carries the
        // display name too.
        SendEmailRequest.Builder request = SendEmailRequest.builder()
                .fromEmailAddress(from(mail))
                .content(content -> content.raw(message -> message.data(SdkBytes.fromByteArray(raw))))
                .overrideConfiguration(config -> config.apiCallTimeout(properties.sendTimeout()));
        if (properties.configurationSet() != null && !properties.configurationSet().isBlank()) {
            request.configurationSetName(properties.configurationSet());
        }
        try {
            return ses.sendEmail(request.build()).messageId();
        } catch (TooManyRequestsException | LimitExceededException ex) {
            throw refused("ses_throttled", true, ex, mail);
        } catch (SendingPausedException | AccountSuspendedException ex) {
            throw refused("ses_paused", true, ex, mail);
        } catch (MessageRejectedException | MailFromDomainNotVerifiedException | BadRequestException
                 | NotFoundException ex) {
            throw refused("ses_rejected", false, ex, mail);
        } catch (AwsServiceException ex) {
            // Errors SES does not model, such as AccessDenied from IAM.
            throw refused("ses_error", true, ex, mail);
        } catch (SdkException ex) {
            log.warn("SES could not be reached: {}", ex.getClass().getSimpleName());
            throw new MailSendException("ses_error", true);
        }
    }

    /** {@code CRM Calendar <no-reply@…>}, the name MIME-encoded when it is not ASCII. */
    static String from(OutgoingMail mail) {
        if (mail.fromName() == null || mail.fromName().isBlank()) {
            return mail.fromAddress();
        }
        try {
            return new InternetAddress(mail.fromAddress(), mail.fromName(), "UTF-8").toString();
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Logs SES's error code, status and message with every address masked
     * (the sender as {@code <from>}, any other as {@code <address>}), so a
     * failure explains itself without an address reaching the logs.
     */
    private static MailSendException refused(String code, boolean retryable, AwsServiceException ex,
            OutgoingMail mail) {
        String errorCode = ex.awsErrorDetails() == null ? null : ex.awsErrorDetails().errorCode();
        String detail = ex.awsErrorDetails() == null ? null : ex.awsErrorDetails().errorMessage();
        log.warn("SES refused the message ({}): {} {} {}", code, errorCode, ex.statusCode(), mask(detail, mail));
        return new MailSendException(code, retryable);
    }

    static String mask(String text, OutgoingMail mail) {
        if (text == null) {
            return "";
        }
        String masked = mail.fromAddress() == null ? text : text.replace(mail.fromAddress(), "<from>");
        return ADDRESS.matcher(masked).replaceAll("<address>");
    }
}
