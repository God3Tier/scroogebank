package bank.engagement.notifications;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SesMailChannelTest {

    private final SesV2Client ses = mock(SesV2Client.class);
    private final MailProperties properties = new MailProperties("ses", "no-reply@mail.example.test",
            "mail.example.test", "engagement-test", "http://localhost:5173", "ap-southeast-1", null,
            Duration.ZERO, Duration.ofSeconds(10), new MailProperties.Dispatcher(null, 0, null, List.of()));
    private final SesMailChannel channel = new SesMailChannel(ses, properties,
            Clock.fixed(Instant.parse("2026-10-21T02:00:00Z"), ZoneOffset.UTC));

    private final OutgoingMail mail = new OutgoingMail("CRM Calendar", "no-reply@mail.example.test",
            "synthetic.agent@example.test", null, "Reminder", "Text", "<p>Text</p>", null);

    @Test
    void sendsRawMimeWithTheConfigurationSetAndKeepsTheMessageId() throws Exception {
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("ses-1").build());

        assertThat(channel.send(mail)).isEqualTo("ses-1");

        ArgumentCaptor<SendEmailRequest> request = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(request.capture());
        assertThat(request.getValue().configurationSetName()).isEqualTo("engagement-test");
        assertThat(request.getValue().fromEmailAddress()).isEqualTo("CRM Calendar <no-reply@mail.example.test>");
        MimeMessage message = MimeMessageFactoryTest.parse(request.getValue().content().raw().data().asByteArray());
        assertThat(message.getSubject()).isEqualTo("Reminder");
    }

    @Test
    void theSenderKeepsItsDisplayNameEncodedWhenNotAscii() throws Exception {
        OutgoingMail named = new OutgoingMail("Zoë Tan via Scrooge Bank", "no-reply@mail.example.test",
                "synthetic.client@example.test", "synthetic.agent@example.test", "Hello", "Text", "<p>Text</p>",
                null);

        String from = SesMailChannel.from(named);

        assertThat(from).startsWith("=?UTF-8?").endsWith(" <no-reply@mail.example.test>");
        assertThat(new InternetAddress(from).getPersonal()).isEqualTo("Zoë Tan via Scrooge Bank");
        assertThat(SesMailChannel.from(new OutgoingMail(null, "no-reply@mail.example.test",
                "synthetic.agent@example.test", null, "Reminder", "Text", "<p>Text</p>", null)))
                .isEqualTo("no-reply@mail.example.test");
    }

    @Test
    void throttlingIsRetryableAndRejectionIsNot() {
        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(TooManyRequestsException.builder().build());
        assertThatThrownBy(() -> channel.send(mail))
                .isInstanceOfSatisfying(MailSendException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("ses_throttled");
                    assertThat(ex.retryable()).isTrue();
                });

        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(MessageRejectedException.builder().build());
        assertThatThrownBy(() -> channel.send(mail))
                .isInstanceOfSatisfying(MailSendException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("ses_rejected");
                    assertThat(ex.retryable()).isFalse();
                });
    }

    @Test
    void anUnmodelledErrorSuchAsAccessDeniedIsAnSesError() {
        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(AwsServiceException.builder()
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").errorMessage("denied").build())
                .build());

        assertThatThrownBy(() -> channel.send(mail))
                .isInstanceOfSatisfying(MailSendException.class, ex -> assertThat(ex.code()).isEqualTo("ses_error"));
    }

    @Test
    void errorMessagesAreLoggedWithoutAddresses() {
        String message = "User `arn:aws:sts::123456789012:assumed-role/task/1' is not authorized to perform "
                + "`ses:SendRawEmail' on resource `arn:aws:ses:ap-southeast-1:123456789012:identity/"
                + "synthetic.client@example.test'. From no-reply@mail.example.test (to synthetic.agent@example.test)";

        String masked = SesMailChannel.mask(message, mail);

        assertThat(masked)
                .contains("identity/<address>'")
                .contains("From <from> (to <address>)")
                .doesNotContain("example.test");
    }
}
