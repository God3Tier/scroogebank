package bank.alert.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuerQueryBuilderTest {

    private final IssuerQueryBuilder builder = new IssuerQueryBuilder();

    @Test
    void buildsOneFocusedQueryPerTopicWithoutSectorOrPrivateTerms() {
        List<IssuerSearchQuery> queries = builder.build(List.of(
                new InstrumentSearchTerms("D05", "DBS Group Holdings Ltd", "Financials",
                        List.of("DBS"), List.of("earnings", "appointment"),
                        List.of("sgx.com", "dbs.com"))), List.of("reuters.com"), 4);

        assertThat(queries).hasSize(IssuerQueryBuilder.QUERIES_PER_INSTRUMENT).hasSize(2);
        assertThat(queries.get(0).query()).contains("D05", "DBS Group Holdings Ltd", "DBS", "earnings", "results")
                .doesNotContain("merger", "buyback", "default");
        assertThat(queries.get(1).query()).contains("D05", "DBS Group Holdings Ltd", "DBS", "acquisition", "buyback")
                .doesNotContain("earnings", "results");
        assertThat(queries).allSatisfy(query -> {
            assertThat(query.query()).doesNotContain("Financials", "syn-client", "account", "holding", " OR ");
            assertThat(query.approvedDomains()).containsExactly("sgx.com", "dbs.com", "reuters.com");
            assertThat(query.issuerTerms()).containsExactly("DBS Group Holdings Ltd", "DBS");
            assertThat(query.eventTerms()).containsExactly("earnings", "appointment");
        });
    }

    @Test
    void buildsQueriesForPreviouslyUnseenTickersAcrossMarkets() {
        List<IssuerSearchQuery> queries = builder.build(List.of(
                terms("7203", "Toyota Motor Corporation", List.of("Toyota"),
                        List.of("global.toyota", "jpx.co.jp")),
                terms("TSM", "Taiwan Semiconductor Manufacturing Company Limited",
                        List.of("TSMC", "Taiwan Semiconductor"), List.of("tsmc.com", "nyse.com"))),
                List.of("reuters.com"), 4);

        assertThat(queries).hasSize(4);
        assertThat(queries.get(0).query()).contains("7203", "Toyota Motor Corporation", "Toyota");
        assertThat(queries.get(1).query()).contains("7203", "Toyota Motor Corporation", "Toyota");
        assertThat(queries.get(0).approvedDomains())
                .containsExactly("global.toyota", "jpx.co.jp", "reuters.com");
        assertThat(queries.get(2).query()).contains("TSM", "Taiwan Semiconductor");
        assertThat(queries.get(3).issuerTerms()).containsExactly(
                "Taiwan Semiconductor Manufacturing Company Limited", "TSMC", "Taiwan Semiconductor");
    }

    @Test
    void agentAddedDomainsAreSearchedButAreNotTreatedAsOfficial() {
        List<IssuerSearchQuery> queries = builder.build(List.of(
                new InstrumentSearchTerms("D05", "DBS Group Holdings Ltd", "Financials", List.of("DBS"),
                        List.of("earnings"), List.of("sgx.com", "dbs.com"), List.of("businesstimes.com.sg"))),
                List.of("reuters.com"), 4);

        assertThat(queries).hasSize(2).allSatisfy(query -> {
            assertThat(query.approvedDomains())
                    .containsExactly("sgx.com", "dbs.com", "reuters.com", "businesstimes.com.sg");
            assertThat(query.issuerDomains()).containsExactly("sgx.com", "dbs.com");
            // The strict gate applies: the company must be named in the title.
            assertThat(query.accepts(new PublicSearchResult("r1", "https://www.businesstimes.com.sg/a",
                    "DBS reports record earnings", java.time.Instant.parse("2026-11-04T01:00:00Z"),
                    java.time.Instant.parse("2026-11-04T02:00:00Z"), null))).isTrue();
            assertThat(query.accepts(new PublicSearchResult("r2", "https://www.businesstimes.com.sg/b",
                    "Bank reports record earnings", java.time.Instant.parse("2026-11-04T01:00:00Z"),
                    java.time.Instant.parse("2026-11-04T02:00:00Z"), "DBS Group said profit rose."))).isFalse();
        });
    }

    @Test
    void refusesToSilentlyDropMoreInstrumentsThanQueries() {
        List<InstrumentSearchTerms> catalogue = IntStream.range(0, 3)
                .mapToObj(index -> new InstrumentSearchTerms("T" + index, "Issuer " + index,
                        "Sector " + index, List.of(), List.of("earnings")))
                .toList();

        assertThatThrownBy(() -> builder.build(catalogue, List.of("reuters.com"), 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded query capacity");
    }

    private static InstrumentSearchTerms terms(
            String ticker, String issuer, List<String> aliases, List<String> domains) {
        return new InstrumentSearchTerms(ticker, issuer, "Test sector", aliases,
                List.of("earnings", "appointment"), domains);
    }
}
