package bank.engagement.integration.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param userPoolId the CRM user pool; blank means no agent can be looked up
 * @param region     the pool's region
 * @param endpoint   an endpoint override for local use; blank for AWS
 * @param cacheTtl   how long a lookup is reused (ADR-0053 decision 4)
 */
@ConfigurationProperties("engagement.identity")
public record IdentityProperties(String userPoolId, String region, String endpoint, Duration cacheTtl) {

    public IdentityProperties {
        region = region == null || region.isBlank() ? "ap-southeast-1" : region;
        cacheTtl = cacheTtl == null ? Duration.ofMinutes(15) : cacheTtl;
    }
}
