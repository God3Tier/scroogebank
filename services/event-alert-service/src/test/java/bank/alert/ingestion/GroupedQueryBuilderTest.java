package bank.alert.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupedQueryBuilderTest {

    private final GroupedQueryBuilder builder = new GroupedQueryBuilder();

    @Test
    void groupsPublicTermsWithoutEmbeddingInternalInstrumentIds() {
        List<String> queries = builder.build(List.of(
                new InstrumentSearchTerms("SYN", "Synthetic Holdings Ltd", List.of("Synthetic Holdings"),
                        List.of("earnings", "regulatory"))), 4);

        assertThat(queries).singleElement()
                .asString()
                .contains("\"SYN\"", "\"Synthetic Holdings Ltd\"", "\"earnings\"")
                .doesNotContain("syn-client", "account", "holding");
    }

    @Test
    void refusesToSilentlyDropAnOversizedCatalogue() {
        List<InstrumentSearchTerms> catalogue = IntStream.range(0, 33)
                .mapToObj(index -> new InstrumentSearchTerms("T" + index, "Issuer " + index,
                        List.of(), List.of("earnings")))
                .toList();

        assertThatThrownBy(() -> builder.build(catalogue, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded grouped-query capacity");
    }
}
