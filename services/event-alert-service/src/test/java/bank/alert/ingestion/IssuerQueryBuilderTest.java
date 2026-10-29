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
    void refusesToSilentlyDropMoreInstrumentsThanQueries() {
        List<InstrumentSearchTerms> catalogue = IntStream.range(0, 5)
                .mapToObj(index -> new InstrumentSearchTerms("T" + index, "Issuer " + index,
                        "Sector " + index, List.of(), List.of("earnings")))
                .toList();

        assertThatThrownBy(() -> builder.build(catalogue, List.of("reuters.com"), 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded query capacity");
    }
}
