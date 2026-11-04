package bank.alert.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchPropertiesTest {

    @Test
    void nothingSetMeansNoResearchRequest() {
        assertThat(new ResearchProperties(null, null, null, null).requested()).isFalse();
        assertThat(new ResearchProperties(" ", "", null, null).requested()).isFalse();
    }

    @Test
    void eitherValueMakesItARequestSoAHalfFilledOneFailsClosed() {
        ResearchProperties onlyCompany = new ResearchProperties("SGX-D05", null, null, null);

        assertThat(onlyCompany.requested()).isTrue();
        assertThatThrownBy(onlyCompany::validate).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(new ResearchProperties(null, "example.com", null, null)::validate)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLookbackDefaultsToFourteenDays() {
        assertThat(new ResearchProperties("SGX-D05", "example.com", null, null).lookback()).isEqualTo(Duration.ofDays(14));
    }

    @Test
    void aCompanyADomainAndASensibleLookbackAreValid() {
        assertThatCode(() -> new ResearchProperties("SGX-D05", "example.com", Duration.ofDays(14), "c1").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void aLookbackOutsideOneHourToThirtyOneDaysIsRefused() {
        assertThatThrownBy(() -> new ResearchProperties("SGX-D05", "example.com", Duration.ofMinutes(10), null).validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchProperties("SGX-D05", "example.com", Duration.ofDays(60), null).validate())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
