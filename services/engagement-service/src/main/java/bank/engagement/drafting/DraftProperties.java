package bank.engagement.drafting;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param expireAfter an open draft not changed for this long is discarded and
 *                    its text cleared (ADR-0068 decision 4)
 */
@ConfigurationProperties("engagement.drafts")
public record DraftProperties(Duration expireAfter) {

    public DraftProperties {
        expireAfter = expireAfter == null ? Duration.ofDays(14) : expireAfter;
    }
}
