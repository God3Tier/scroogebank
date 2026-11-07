package bank.alert.ingestion;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IssuerSearchQueryTest {

    private final IssuerSearchQuery query = new IssuerSearchQuery("DBS news",
            List.of("DBS Group Holdings Ltd", "DBS"),
            List.of("earnings", "financial results", "profit", "appointment", "steps down"),
            List.of("sgx.com", "dbs.com"),
            List.of("sgx.com", "reuters.com"));

    @Test
    void acceptsOnlyNamedIssuerEvents() {
        assertThat(query.accepts(result("DBS announces appointment of independent director", null))).isTrue();
        assertThat(query.accepts(result("DBS financial results", "Quarterly update."))).isTrue();
        assertThat(query.accepts(result("DBS posts record profit", "Quarterly update."))).isTrue();
        assertThat(query.accepts(result("Appointment of Non-Executive Director", "DBS Group announced the appointment."))).isTrue();
        assertThat(query.accepts(result("Appointment of Non-Executive Director", "DBS Group announced the appointment.",
                "https://www.reuters.com/world/appointment"))).isFalse();
        assertThat(query.accepts(result("China exports stabilize inflation", "DBS expects stronger profit."))).isFalse();
        assertThat(query.accepts(result("What to watch as DBS reports earnings", null))).isFalse();
        assertThat(query.accepts(result("DBS set to report earnings tomorrow", null))).isFalse();
        assertThat(query.accepts(result("DBS expected to report earnings tomorrow", null))).isFalse();
        assertThat(query.accepts(result("DBS earnings key to gauging regional demand", null))).isFalse();
        assertThat(query.accepts(result("Apple announces new product", "Launch event."))).isFalse();
        assertThat(query.accepts(result("DBS launches a mobile application", "New customer features."))).isFalse();
        assertThat(query.accepts(result("D05 earnings rise", "Quarterly profit increased."))).isFalse();
    }

    @Test
    void theWordsAddedOnTheSeventhOfNovemberAcceptRealHeadlinesButNotMarketChatter() {
        IssuerSearchQuery wider = new IssuerSearchQuery("Microsoft news",
                List.of("Microsoft Corporation", "Microsoft"),
                List.of("earnings", "deal", "winding down", "probe", "weighs", "plans", "warns", "sues", "ban"),
                List.of("microsoft.com"), List.of("reuters.com", "bloomberg.com"));
        String url = "https://www.bloomberg.com/news/1";
        assertThat(wider.accepts(result("Microsoft Is Winding Down Oracle Deal to Rent AI Cloud Capacity", null, url))).isTrue();
        assertThat(wider.accepts(result("Microsoft plans to use fewer neoclouds as AI buildout shows signs of strain", null, url))).isTrue();
        assertThat(wider.accepts(result("Democrats to probe Microsoft's cloud contracts", null, url))).isTrue();
        // Still needs the company in the title, and a market roundup with no event word is still out.
        assertThat(wider.accepts(result("Nvidia weighs playing the cosigner for Nscale's data center", null, url))).isFalse();
        assertThat(wider.accepts(result("Stocks making the biggest moves midday: Microsoft, Moderna, IonQ", null, url))).isFalse();
    }

    private static PublicSearchResult result(String title, String snippet) {
        return result(title, snippet, "https://www.sgx.com/news/1");
    }

    private static PublicSearchResult result(String title, String snippet, String url) {
        return new PublicSearchResult("id", url, title,
                Instant.parse("2026-10-29T00:00:00Z"), Instant.parse("2026-10-29T01:00:00Z"), null, snippet);
    }
}
