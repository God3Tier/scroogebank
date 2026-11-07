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
import lombok.experimental.UtilityClass;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Builds the raw MIME message SES sends (ADR-0053 decision 6). A message with
 * attachments is {@code multipart/mixed}: a {@code multipart/alternative} of
 * plain text and HTML (and {@code text/calendar} for an invite), then each
 * file: an invite's {@code invite.ics} (ADR-0052 decision 3), or a News Alert's
 * report PDF (news-alert-drafts plan 5.6). Any other message is the
 * alternative alone.
 */
@UtilityClass
public final class MimeMessageFactory {

    private static final Session SESSION = Session.getInstance(new Properties());

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

            List<MimeBodyPart> attachments = new ArrayList<>();
            if (mail.calendar() != null) {
                byte[] ics = mail.calendar().icsText().getBytes(StandardCharsets.UTF_8);
                alternative.addBodyPart(dataPart(ics,
                        "text/calendar; charset=UTF-8; method=" + mail.calendar().method()));
                MimeBodyPart invite = dataPart(ics, "application/ics; name=\"invite.ics\"");
                invite.setFileName("invite.ics");
                invite.setDisposition(Part.ATTACHMENT);
                attachments.add(invite);
            }
            for (OutgoingMail.Attachment file : mail.attachments()) {
                MimeBodyPart part = dataPart(file.content(), file.contentType() + "; name=\"" + file.fileName() + "\"");
                part.setFileName(file.fileName());
                part.setDisposition(Part.ATTACHMENT);
                attachments.add(part);
            }

            if (attachments.isEmpty()) {
                message.setContent(alternative);
            } else {
                MimeBodyPart body = new MimeBodyPart();
                body.setContent(alternative);
                MimeMultipart mixed = new MimeMultipart("mixed");
                mixed.addBodyPart(body);
                for (MimeBodyPart attachment : attachments) {
                    mixed.addBodyPart(attachment);
                }
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
