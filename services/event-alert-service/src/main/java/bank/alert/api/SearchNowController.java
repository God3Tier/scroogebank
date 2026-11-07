package bank.alert.api;

import bank.alert.research.SearchOnAddService;
import bank.alert.service.HoldingsOverview;
import bank.alert.service.SourceDomainException;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Search now (ADR-0081): one company, or every company the caller may search, using every website. */
@RestController
@RequestMapping("/api/alerts/search-now")
public class SearchNowController {

    private final HoldingsOverview holdings;
    private final SearchOnAddService searchOnAdd;

    public SearchNowController(HoldingsOverview holdings, SearchOnAddService searchOnAdd) {
        this.holdings = holdings;
        this.searchOnAdd = searchOnAdd;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    public SearchRunResponse searchNow(
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
            @Valid @RequestBody(required = false) SearchNowRequest request,
            Authentication authentication) {
        boolean admin = SearchWebsiteController.isAdmin(authentication);
        // An agent searches only what their clients hold; an admin may search any company someone holds.
        List<String> allowed = admin ? holdings.allHeldInstrumentIds() : holdings.instrumentIdsFor(authentication.getName());
        String wanted = request == null ? null : request.instrumentId();
        List<String> instruments = wanted == null || wanted.isBlank() ? allowed : List.of(wanted.strip());
        if (!allowed.containsAll(instruments)) {
            throw admin ? SourceDomainException.notFound() : SourceDomainException.forbidden();
        }
        return SearchRunResponse.of(searchOnAdd.manual(instruments, authentication.getName(),
                correlationId == null ? UUID.randomUUID() : correlationId));
    }
}
