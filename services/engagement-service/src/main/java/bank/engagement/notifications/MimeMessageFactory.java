package bank.engagement.notifications;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;

/**
 * Builds the raw MIME message SES sends (ADR-0053 decision 6). An invite is
 * {@code multipart/mixed}: a {@code multipart/alternative} of plain text, HTML
 * and {@code text/calendar}, plus the same calendar as {@code invite.ics}
 * (ADR-0052 decision 3). Any other message is the alternative alone.
 */
public final class MimeMessageFactory {

    private static final Session SESSION = Session.getInstance(new Properties());

    private MimeMessageFactory() {
    }

    public static byte[] build(OutgoingMail mail, Date sentDate) {
        try {
            MimeMessage message = new MimeMessage(SESSION);
            message.setFrom(new InternetAddress(mail.fromAddress(), mail.fromName(), "UTF-8"));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(mail.to()));
            if (mail.replyTo() != null) {
                message.setReplyTo(new InternetAddress[] {new InternetAddress(mail.replyTo())});
            }
            message.setSubject(mail.subject(), "UTF-8");
            message.setSentDate(sentDate);

            MimeMultipart alternative = new MimeMultipart("alternative");
            alternative.addBodyPart(textPart(mail.textBody(), "plain"));
            alternative.addBodyPart(textPart(mail.htmlBody(), "html"));

            if (mail.calendar() == null) {
                message.setContent(alternative);
            } else {
                byte[] ics = mail.calendar().icsText().getBytes(StandardCharsets.UTF_8);
                alternative.addBodyPart(dataPart(ics,
                        "text/calendar; charset=UTF-8; method=" + mail.calendar().method()));

                MimeBodyPart body = new MimeBodyPart();
                body.setContent(alternative);
                MimeBodyPart attachment = dataPart(ics, "application/ics; name=\"invite.ics\"");
                attachment.setFileName("invite.ics");
                attachment.setDisposition(Part.ATTACHMENT);

                MimeMultipart mixed = new MimeMultipart("mixed");
                mixed.addBodyPart(body);
                mixed.addBodyPart(attachment);
                message.setContent(mixed);
            }
            message.saveChanges();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            message.writeTo(out);
            return out.toByteArray();
        } catch (MessagingException | IOException ex) {
            throw new MailSendException("mime_build_failed", false);
        }
    }

    private static MimeBodyPart textPart(String text, String subtype) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setText(text, "UTF-8", subtype);
        return part;
    }

    private static MimeBodyPart dataPart(byte[] bytes, String contentType) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, contentType)));
        part.setHeader("Content-Type", contentType);
        return part;
    }
}
