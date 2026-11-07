package bank.alert.api;

import bank.alert.research.SearchOnAddService;

import java.time.Instant;
import java.util.List;

/**
 * What happened to a search asked for when a website was added or Search now was pressed (ADR-0081).
 * {@code outcome} is STARTED, COOLDOWN, DAILY_LIMIT, OFF, FAILED or NOTHING_TO_SEARCH. {@code started} names
 * the companies being searched and {@code cooldown} those searched too recently, with when they can be again.
 */
public record SearchRunResponse(String outcome, List<String> started, List<Waiting> cooldown, long lookbackDays) {

    public record Waiting(String instrumentId, Instant availableAt) {}

    static SearchRunResponse of(SearchOnAddService.Result result) {
        return new SearchRunResponse(result.outcome().name(), result.started(),
                result.cooldown().stream().map(s -> new Waiting(s.instrumentId(), s.availableAt())).toList(),
                result.lookbackDays());
    }
}
