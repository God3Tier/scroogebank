package bank.alert.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuerQueryBuilderTest {

    private final IssuerQueryBuilder builder = new IssuerQueryBuilder();

    @Test
    void buildsOneIssuerQueryWithoutSectorOrPrivateTerms() {
        List<IssuerSearchQuery> queries = builder.build(List.of(
                new InstrumentSearchTerms("D05", "DBS Group Holdings Ltd", "Financials",
                        List.of("DBS"), List.of("earnings", "appointment"),
                        List.of("sgx.com", "dbs.com"))), List.of("reuters.com"), 4);

        assertThat(queries).singleElement().satisfies(query -> {
            assertThat(query.query()).contains("D05", "DBS Group Holdings Ltd", "DBS", "earnings", "results")
                    .doesNotContain("divestment", "buyback", "default")
                    .doesNotContain("Financials", "syn-client", "account", "holding", " OR ");
            assertThat(query.approvedDomains()).containsExactly("sgx.com", "dbs.com", "reuters.com");
            assertThat(query.issuerTerms()).containsExactly("DBS Group Holdings Ltd", "DBS");
        });
    }

    @Test
    void buildsQueriesForPreviouslyUnseenTickersAcrossMarkets() {
        List<IssuerSearchQuery> queries = builder.build(List.of(
                terms("7203", "Toyota Motor Corporation", List.of("Toyota"),
                        List.of("global.toyota", "jpx.co.jp")),
                terms("BHP", "BHP Group Limited", List.of("BHP"),
                        List.of("bhp.com", "asx.com.au")),
                terms("SAP", "SAP SE", List.of(), List.of("sap.com")),
                terms("TSM", "Taiwan Semiconductor Manufacturing Company Limited",
                        List.of("TSMC", "Taiwan Semiconductor"), List.of("tsmc.com", "nyse.com"))),
                List.of("reuters.com"), 4);

        assertThat(queries).hasSize(4);
        assertThat(queries.get(0).query()).contains("7203", "Toyota Motor Corporation", "Toyota");
        assertThat(queries.get(0).approvedDomains())
                .containsExactly("global.toyota", "jpx.co.jp", "reuters.com");
        assertThat(queries.get(1).query()).contains("BHP", "BHP Group Limited");
        assertThat(queries.get(2).query()).contains("SAP", "SAP SE");
        assertThat(queries.get(3).issuerTerms()).containsExactly(
                "Taiwan Semiconductor Manufacturing Company Limited", "TSMC", "Taiwan Semiconductor");
    }

    @Test
    void refusesToSilentlyDropMoreInstrumentsThanQueries() {
        List<InstrumentSearchTerms> catalogue = IntStream.range(0, 5)
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
