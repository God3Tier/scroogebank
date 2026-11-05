package bank.alert.api;

import bank.alert.service.SearchWebsiteService.Website;

import java.time.Instant;
import java.util.UUID;

/** The reply to adding a website (ADR-0082): the entry, and what happened to the search started for it. */
public record AddedWebsiteResponse(UUID id, String domain, Instant addedAt, boolean addedByYou, SearchRunResponse search) {

    static AddedWebsiteResponse of(Website added, SearchRunResponse search) {
        return new AddedWebsiteResponse(added.id(), added.domain(), added.addedAt(), added.addedByYou(), search);
    }
}
