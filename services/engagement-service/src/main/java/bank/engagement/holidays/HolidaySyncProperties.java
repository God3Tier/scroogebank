package bank.engagement.holidays;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The holiday sync (ADR-0057).
 *
 * @param enabled   fetch MOM's list from data.gov.sg; off, the migrations' rows are all there is
 * @param baseUrl   data.gov.sg
 * @param datasetId MOM's consolidated "Singapore Public Holidays" dataset, updated each year
 * @param timeout   connect and read timeout for each page
 *
 * <p>{@code initial-delay} (default 1m) and {@code interval} (default 24h) are
 * read by {@link HolidaySyncScheduler}.
 */
@ConfigurationProperties("engagement.holidays.sync")
public record HolidaySyncProperties(boolean enabled, String baseUrl, String datasetId, Duration timeout) {

    public HolidaySyncProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://data.gov.sg" : baseUrl;
        datasetId = datasetId == null || datasetId.isBlank() ? "d_8ef23381f9417e4d4254ee8b4dcdb176" : datasetId;
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }
}
