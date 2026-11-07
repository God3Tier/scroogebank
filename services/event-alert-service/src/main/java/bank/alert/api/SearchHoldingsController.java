package bank.alert.api;

import bank.alert.service.HoldingsOverview;
import bank.alert.service.HoldingsOverview.CompanyHoldings;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Which clients hold each company that is searched (ADR-0082): an agent's own clients, or all for an admin. */
@RestController
@RequestMapping("/api/alerts/search-holdings")
public class SearchHoldingsController {

    private final HoldingsOverview holdings;

    public SearchHoldingsController(HoldingsOverview holdings) {
        this.holdings = holdings;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    public List<CompanyHoldings> list(Authentication authentication) {
        return holdings.forCaller(authentication.getName(), SearchWebsiteController.isAdmin(authentication));
    }
}
