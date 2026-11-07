package bank.engagement.drafting;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI drafting settings (ADR-0054 decision 7; model per ADR-0067).
 *
 * @param enabled the kill switch; off means every draft is a template
 * @param modelId Nova Lite's Asia-Pacific inference profile by default
 */
@ConfigurationProperties("engagement.ai")
public record AiProperties(
        boolean enabled,
        String region,
        String modelId,
        Float temperature,
        Integer maxOutputTokens,
        Duration timeout,
        Integer dailyDraftsPerAgent,
        Long monthlyTokenQuota) {

    public AiProperties {
        region = region == null || region.isBlank() ? "ap-southeast-1" : region;
        modelId = modelId == null || modelId.isBlank() ? "apac.amazon.nova-lite-v1:0" : modelId;
        temperature = temperature == null ? 0.6F : temperature;
        maxOutputTokens = maxOutputTokens == null ? 600 : maxOutputTokens;
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
        dailyDraftsPerAgent = dailyDraftsPerAgent == null ? 20 : dailyDraftsPerAgent;
        monthlyTokenQuota = monthlyTokenQuota == null ? 300_000L : monthlyTokenQuota;
        if (modelId.startsWith("global.")) {
            throw new IllegalArgumentException("global inference profiles are not allowed (ADR-0067)");
        }
    }
}
