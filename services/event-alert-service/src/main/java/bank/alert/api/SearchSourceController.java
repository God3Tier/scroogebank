package bank.alert.api;

import bank.alert.research.SearchOnAddService;
import bank.alert.service.SearchSourceService;
import bank.alert.service.SearchSourceService.CompanySources;
import bank.alert.service.SearchSourceService.CustomDomain;
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

import java.util.List;
import java.util.UUID;

/** Per-company search domains that agents add (ADR-0079). */
@RestController
@RequestMapping("/api/alerts/search-sources")
public class SearchSourceController {

    private final SearchSourceService service;
    private final SearchOnAddService searchOnAdd;

    public SearchSourceController(SearchSourceService service, SearchOnAddService searchOnAdd) {
        this.service = service;
        this.searchOnAdd = searchOnAdd;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    public List<CompanySources> list(Authentication authentication) {
        return service.list(authentication.getName(), isAdmin(authentication));
    }

    @PostMapping("/{instrumentId}/domains")
    @PreAuthorize("hasRole('AGENT')")
    @ResponseStatus(HttpStatus.CREATED)
    public AddedSourceDomain add(
            @PathVariable String instrumentId,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
            @Valid @RequestBody AddSourceDomainRequest request,
            Authentication authentication) {
        UUID correlation = correlationId == null ? UUID.randomUUID() : correlationId;
        CustomDomain added = service.add(instrumentId, authentication.getName(), correlation, request.domain());
        // The domain is saved and committed by now; the search is a bonus and never fails the add (ADR-0080).
        SearchOnAddService.Outcome research = searchOnAdd.request(
                instrumentId, added.domain(), authentication.getName(), correlation);
        return AddedSourceDomain.of(added, research, searchOnAdd.lookbackDays());
    }

    @DeleteMapping("/{instrumentId}/domains/{domainId}")
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(
            @PathVariable String instrumentId,
            @PathVariable UUID domainId,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
            Authentication authentication) {
        service.deactivate(instrumentId, domainId, authentication.getName(), isAdmin(authentication),
                correlationId == null ? UUID.randomUUID() : correlationId);
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
