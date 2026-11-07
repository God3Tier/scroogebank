package bank.alert.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Tavily, three credits (ADR-0079). Runs only when ALERT_TAVILY_API_KEY is set:
 * it proves that an agent-added domain is sent to Tavily and that Tavily then returns
 * results from nowhere else. Not part of the normal build.
 */
@EnabledIfEnvironmentVariable(named = "ALERT_TAVILY_API_KEY", matches = ".+")
class CustomDomainTavilyLiveTest {

    private static final Instant FROM = Instant.parse("2026-07-29T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-12T00:00:00Z");
    private static final String CUSTOM = "businesstimes.com.sg";

    private final TavilySearchProvider provider = new TavilySearchProvider(
            URI.create("https://api.tavily.com/search"), System.getenv("ALERT_TAVILY_API_KEY"),
            Duration.ofSeconds(30), 262_144, new ObjectMapper(), Clock.systemUTC(), HttpClient.newHttpClient());

    private static boolean onDomain(String url, String domain) {
        String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
        return host.equals(domain) || host.endsWith("." + domain);
    }

    @Test
    void theCustomDomainAloneRestrictsTheSearchToThatDomain() {
        List<PublicSearchResult> results = provider.search(new PublicSearchRequest(
                "DBS Group Holdings Ltd DBS earnings results profit", List.of(CUSTOM), FROM, TO, 10));

        System.out.println("LIVE custom-only: " + results.size() + " results");
        results.forEach(result -> System.out.println("  " + result.url() + " | " + result.title()));
        assertThat(results).allSatisfy(result -> assertThat(onDomain(result.url(), CUSTOM)).isTrue());
    }

    @Test
    void theBuiltSearchesCarryTheCustomDomainAndOnlyReturnApprovedDomains() {
        List<IssuerSearchQuery> queries = new IssuerQueryBuilder().build(List.of(
                new InstrumentSearchTerms("D05", "DBS Group Holdings Ltd", "Financials", List.of("DBS"),
                        List.of("earnings", "results", "profit", "revenue", "guidance", "financial results"),
                        List.of("sgx.com", "dbs.com"), List.of(CUSTOM))),
                List.of("reuters.com", "cnbc.com", "ft.com"));
        assertThat(queries).hasSize(2);

        for (IssuerSearchQuery query : queries) {
            assertThat(query.approvedDomains()).contains(CUSTOM);
            List<PublicSearchResult> results = provider.search(new PublicSearchRequest(
                    query.query(), query.approvedDomains(), FROM, TO, 10));
            long accepted = results.stream().filter(query::accepts).count();
            System.out.println("LIVE query \"" + query.query() + "\": " + results.size() + " results, "
                    + accepted + " pass the gate");
            results.forEach(result -> System.out.println("  " + (query.accepts(result) ? "PASS " : "skip ")
                    + result.url() + " | " + result.title()));
            assertThat(results).allSatisfy(result -> assertThat(query.approvedDomains().stream()
                    .anyMatch(domain -> onDomain(result.url(), domain))).isTrue());
        }
    }
}
