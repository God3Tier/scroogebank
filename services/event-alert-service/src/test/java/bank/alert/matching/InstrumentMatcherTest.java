package bank.alert.matching;

import bank.alert.domain.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstrumentMatcherTest {

    private final InstrumentMatcher matcher = new InstrumentMatcher();

    private static Instrument instrument(String id, String ticker, String issuer, String sector, boolean active) {
        Instrument instrument = mock(Instrument.class);
        when(instrument.getId()).thenReturn(id);
        when(instrument.getTicker()).thenReturn(ticker);
        when(instrument.getIssuerName()).thenReturn(issuer);
        when(instrument.getSector()).thenReturn(sector);
        when(instrument.isActive()).thenReturn(active);
        return instrument;
    }

    private final List<Instrument> master = List.of(
            instrument("SGX-DBS", "D05", "DBS Group Holdings", "Banking", true),
            instrument("SGX-OCBC", "O39", "Oversea-Chinese Banking Corporation", "Banking", true),
            instrument("SGX-OLD", "OLD1", "Old Holdings", "Banking", false),
            instrument("SGX-TEL", "Z74", "Singtel", "Telecommunications", true));

    @Test
    void aTickerAndTheIssuerNameIsTheMostDirectMatch() {
        List<InstrumentMatch> matches = matcher.match(
                "DBS Group Holdings (D05) reports record earnings", null, master);

        assertThat(matches).first().satisfies(match -> {
            assertThat(match.instrumentId()).isEqualTo("SGX-DBS");
            assertThat(match.relevance()).isEqualByComparingTo("1.00");
            assertThat(match.reason()).isEqualTo("TICKER_AND_ISSUER");
        });
    }

    @Test
    void aTickerAloneOrAnIssuerAloneIsStillADirectMatch() {
        assertThat(matcher.match("Shares in D05 rose today", null, master))
                .singleElement().satisfies(match -> {
                    assertThat(match.reason()).isEqualTo("TICKER");
                    assertThat(match.relevance()).isEqualByComparingTo("0.80");
                });
        assertThat(matcher.match("Singtel announces a new CEO", "The telco named a successor.", master))
                .singleElement().satisfies(match -> {
                    assertThat(match.instrumentId()).isEqualTo("SGX-TEL");
                    assertThat(match.reason()).isEqualTo("ISSUER");
                });
    }

    @Test
    void aSectorOnlyMentionMatchesEveryActiveInstrumentInTheSectorLessStrongly() {
        List<InstrumentMatch> matches = matcher.match("MAS tightens rules for Banking", null, master);

        assertThat(matches).extracting(InstrumentMatch::instrumentId).containsExactly("SGX-DBS", "SGX-OCBC");
        assertThat(matches).allSatisfy(match -> {
            assertThat(match.reason()).isEqualTo("SECTOR");
            assertThat(match.relevance()).isEqualByComparingTo(InstrumentMatcher.SECTOR_ONLY);
        });
    }

    @Test
    void aTickerIsAnExactCaseSensitiveTokenNotASubstring() {
        assertThat(matcher.match("The XD05Y index and d05 rose", null, master)).isEmpty();
        assertThat(matcher.match("Ticker D05, and O39.", null, master))
                .extracting(InstrumentMatch::instrumentId).containsExactly("SGX-DBS", "SGX-OCBC");
    }

    @Test
    void issuerNamesIgnoreCasePunctuationAndSpacing() {
        assertThat(matcher.match("oversea-chinese   BANKING corporation lifts its dividend", null, master))
                .extracting(InstrumentMatch::instrumentId).contains("SGX-OCBC");
    }

    @Test
    void approvedAliasesMatchNewInstrumentsWithoutTheirTickerOrFullLegalName() {
        Instrument tsmc = instrument("NYSE-TSM", "TSM",
                "Taiwan Semiconductor Manufacturing Company Limited", "Semiconductors", true);
        when(tsmc.getSearchAliases()).thenReturn(List.of("TSMC", "Taiwan Semiconductor"));

        assertThat(matcher.match("TSMC posts record profit", null, List.of(tsmc)))
                .singleElement().satisfies(match -> {
                    assertThat(match.instrumentId()).isEqualTo("NYSE-TSM");
                    assertThat(match.reason()).isEqualTo("ISSUER");
                    assertThat(match.relevance()).isEqualByComparingTo("0.80");
                });
        assertThat(matcher.match("ATSMC launches a product", null, List.of(tsmc))).isEmpty();
    }

    @Test
    void inactiveInstrumentsAreNeverMatchedAndUnnamedOnesAreNeverGuessed() {
        assertThat(matcher.match("Old Holdings OLD1 news", null, master)).isEmpty();
        assertThat(matcher.match("A quiet day on the markets", "Nothing happened.", master)).isEmpty();
    }

    @Test
    void theSameInputAlwaysGivesTheSameMatchesInTheSameOrder() {
        List<InstrumentMatch> first = matcher.match("Banking news for D05 and O39", "Singtel too", master);
        List<InstrumentMatch> second = matcher.match("Banking news for D05 and O39", "Singtel too", master);

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(InstrumentMatch::instrumentId)
                .isSortedAccordingTo(String::compareTo);
        assertThat(first.getFirst().relevance()).isGreaterThanOrEqualTo(new BigDecimal("0.40"));
    }
}
