package bank.engagement.notifications;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class MimeMessageFactoryTest {

    private static final String ICS = "BEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nEND:VCALENDAR\r\n";

    static MimeMessage parse(byte[] raw) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
    }

    @Test
    void inviteIsMixedWithAnAlternativeAndAnIcsAttachment() throws Exception {
        OutgoingMail mail = new OutgoingMail("CRM Calendar", "no-reply@mail.example.test",
                "synthetic.agent@example.test", null, "Invitation: Review", "Plain text", "<p>HTML</p>",
                new OutgoingMail.CalendarPart("REQUEST", ICS));

        MimeMessage message = parse(MimeMessageFactory.build(mail, new Date()));

        assertThat(message.getContentType()).startsWith("multipart/mixed");
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("CRM Calendar");
        Multipart mixed = (Multipart) message.getContent();
        assertThat(mixed.getCount()).isEqualTo(2);

        Multipart alternative = (Multipart) mixed.getBodyPart(0).getContent();
        assertThat(mixed.getBodyPart(0).getContentType()).startsWith("multipart/alternative");
        assertThat(alternative.getCount()).isEqualTo(3);
        assertThat(alternative.getBodyPart(0).getContentType()).startsWith("text/plain");
        assertThat(alternative.getBodyPart(1).getContentType()).startsWith("text/html");
        assertThat(alternative.getBodyPart(2).getContentType())
                .startsWith("text/calendar").contains("method=REQUEST");
        assertThat(read(alternative.getBodyPart(2))).isEqualTo(ICS);

        Part attachment = mixed.getBodyPart(1);
        assertThat(attachment.getFileName()).isEqualTo("invite.ics");
        assertThat(attachment.getDisposition()).isEqualTo(Part.ATTACHMENT);
        assertThat(read(attachment)).isEqualTo(ICS);
    }

    @Test
    void clientEmailIsAnAlternativeWithReplyTo() throws Exception {
        OutgoingMail mail = new OutgoingMail("Synthetic Agent via Scrooge Bank", "no-reply@mail.example.test",
                "synthetic.client@example.test", "synthetic.agent@example.test", "Hello", "Body", "<p>Body</p>", null);

        MimeMessage message = parse(MimeMessageFactory.build(mail, new Date()));

        assertThat(message.getContentType()).startsWith("multipart/alternative");
        assertThat(((InternetAddress) message.getReplyTo()[0]).getAddress()).isEqualTo("synthetic.agent@example.test");
        assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress())
                .isEqualTo("synthetic.client@example.test");
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("Synthetic Agent via Scrooge Bank");
        assertThat(message.getSubject()).isEqualTo("Hello");
    }

    private static String read(Part part) throws Exception {
        try (InputStream in = part.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
