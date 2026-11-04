package bank.alert.api;

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

    public SearchSourceController(SearchSourceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    public List<CompanySources> list(Authentication authentication) {
        return service.list(authentication.getName(), isAdmin(authentication));
    }

    @PostMapping("/{instrumentId}/domains")
    @PreAuthorize("hasRole('AGENT')")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomDomain add(
            @PathVariable String instrumentId,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
            @Valid @RequestBody AddSourceDomainRequest request,
            Authentication authentication) {
        return service.add(instrumentId, authentication.getName(),
                correlationId == null ? UUID.randomUUID() : correlationId, request.domain());
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
