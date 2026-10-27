package bank.engagement.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.net.URI;
import java.time.Clock;

/**
 * The mail channel follows {@code ENGAGEMENT_MAIL_PROVIDER}. With the default,
 * {@code none}, there is no channel: nothing is sent and queued mail waits.
 */
@Configuration
public class MailChannelConfiguration {

    @Bean
    @ConditionalOnMissingBean(MailChannel.class)
    @ConditionalOnProperty(prefix = "engagement.mail", name = "provider", havingValue = "ses")
    MailChannel sesMailChannel(MailProperties properties, Clock clock) {
        var builder = SesV2Client.builder().region(Region.of(properties.region()));
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return new SesMailChannel(builder.build(), properties, clock);
    }

    @Bean
    @ConditionalOnMissingBean(MailChannel.class)
    @ConditionalOnProperty(prefix = "engagement.mail", name = "provider", havingValue = "log")
    MailChannel loggingMailChannel() {
        return new LoggingMailChannel();
    }
}
