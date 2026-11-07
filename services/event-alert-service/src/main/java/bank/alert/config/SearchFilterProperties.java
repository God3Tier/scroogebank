package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * For lower environments only (ADR-0084): when {@code onlyInstrumentId} is set
 * (ALERT_SEARCH_ONLY_INSTRUMENT_ID), a run searches just that one instrument, so a report can be tested at the
 * cost of two searches. It must be an active instrument that passes the eligibility rules; anything else
 * is refused before any search quota is used. Unset, every eligible instrument is searched.
 */
@ConfigurationProperties(prefix = "alert.search")
public record SearchFilterProperties(String onlyInstrumentId) {

    public SearchFilterProperties {
        onlyInstrumentId = onlyInstrumentId == null || onlyInstrumentId.isBlank() ? null : onlyInstrumentId.strip();
    }

    public boolean restricted() {
        return onlyInstrumentId != null;
    }
}
