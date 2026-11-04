package bank.alert.api;

import bank.alert.research.SearchOnAddService;
import bank.alert.service.SearchSourceService.CustomDomain;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * The reply to adding a search domain (ADR-0079): the entry, plus what happened to the search
 * started for it (ADR-0080). {@code research} is STARTED, COOLDOWN, DAILY_LIMIT, OFF or FAILED;
 * the look-back is given only when a search started.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddedSourceDomain(UUID id, String domain, Instant addedAt, boolean addedByYou,
        String research, Long researchLookbackDays) {

    static AddedSourceDomain of(CustomDomain added, SearchOnAddService.Outcome research, long lookbackDays) {
        return new AddedSourceDomain(added.id(), added.domain(), added.addedAt(), added.addedByYou(),
                research.name(), research == SearchOnAddService.Outcome.STARTED ? lookbackDays : null);
    }
}
