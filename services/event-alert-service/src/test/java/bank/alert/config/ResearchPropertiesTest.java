package bank.alert.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchPropertiesTest {

    @Test
    void nothingSetMeansNoResearchRequest() {
        assertThat(new ResearchProperties(null, null, null, null).requested()).isFalse();
        assertThat(new ResearchProperties(List.of(" ", ""), " ", null, null).requested()).isFalse();
    }

    @Test
    void eitherValueMakesItARequestSoAHalfFilledOneFailsClosed() {
        ResearchProperties onlyDomain = new ResearchProperties(null, "example.com", null, null);

        assertThat(onlyDomain.requested()).isTrue();
        assertThatThrownBy(onlyDomain::validate).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ResearchProperties(List.of("SGX-D05"), null, null, null).requested()).isTrue();
    }

    @Test
    void theLookbackDefaultsToFourteenDays() {
        assertThat(new ResearchProperties(List.of("SGX-D05"), "example.com", null, null).lookback()).isEqualTo(Duration.ofDays(14));
    }

    @Test
    void aCompanyListWithoutAWebsiteIsValidBecauseItSearchesEveryWebsite() {
        assertThatCode(() -> new ResearchProperties(List.of("SGX-D05", "SGX-O39"), null, Duration.ofDays(14), "c1").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void duplicatesAndBlanksAreDropped() {
        assertThat(new ResearchProperties(List.of("SGX-D05", " SGX-D05 ", "", "SGX-O39"), null, null, null).instruments())
                .containsExactly("SGX-D05", "SGX-O39");
    }

    @Test
    void aLookbackOutsideOneHourToThirtyOneDaysIsRefused() {
        assertThatThrownBy(() -> new ResearchProperties(List.of("SGX-D05"), "example.com", Duration.ofMinutes(10), null).validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchProperties(List.of("SGX-D05"), "example.com", Duration.ofDays(60), null).validate())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
