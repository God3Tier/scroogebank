package bank.alert.api;

import bank.alert.research.SearchOnAddService;
import bank.alert.service.HoldingsOverview;
import bank.alert.service.SearchWebsiteService;
import bank.alert.service.SearchWebsiteService.Website;
import bank.alert.service.SearchWebsiteService.WebsiteList;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The websites searched for every company (ADR-0082). */
@RestController
@RequestMapping("/api/alerts/search-websites")
public class SearchWebsiteController {

    private final SearchWebsiteService service;
    private final HoldingsOverview holdings;
    private final SearchOnAddService searchOnAdd;

    public SearchWebsiteController(SearchWebsiteService service, HoldingsOverview holdings, SearchOnAddService searchOnAdd) {
        this.service = service;
        this.holdings = holdings;
        this.searchOnAdd = searchOnAdd;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    public WebsiteList list(Authentication authentication) {
        return service.list(authentication.getName(), isAdmin(authentication));
    }

    @PostMapping
    @PreAuthorize("hasRole('AGENT')")
    @ResponseStatus(HttpStatus.CREATED)
    public AddedWebsiteResponse add(
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
            @Valid @RequestBody AddWebsiteRequest request,
            Authentication authentication) {
        UUID correlation = correlationId == null ? UUID.randomUUID() : correlationId;
        Website added = service.add(authentication.getName(), correlation, request.domain());
        // The website is saved and committed by now; the search is a bonus and never fails the add (ADR-0081).
        SearchOnAddService.Result search = searchOnAdd.afterAdd(added.domain(),
                holdings.instrumentIdsFor(authentication.getName()), authentication.getName(), correlation);
        return AddedWebsiteResponse.of(added, SearchRunResponse.of(search));
    }

    @DeleteMapping("/{websiteId}")
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @PathVariable UUID websiteId,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
            Authentication authentication) {
        service.remove(websiteId, authentication.getName(), isAdmin(authentication),
                correlationId == null ? UUID.randomUUID() : correlationId);
    }

    static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
