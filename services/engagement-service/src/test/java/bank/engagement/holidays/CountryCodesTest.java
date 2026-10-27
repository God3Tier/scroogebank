package bank.engagement.holidays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CountryCodesTest {

    @Test
    void recognisesSingaporeHoweverItIsWritten() {
        for (String country : new String[] {"Singapore", " singapore ", "SINGAPORE.", "SG", "s.g.", "SGP",
                "Republic of  Singapore"}) {
            assertThat(CountryCodes.toIso(country)).as(country).contains("SG");
        }
    }

    @Test
    void otherCountriesHaveNoHolidayData() {
        assertThat(CountryCodes.toIso("Malaysia")).isEmpty();
        assertThat(CountryCodes.toIso("Singapore City")).isEmpty();
        assertThat(CountryCodes.toIso("")).isEmpty();
        assertThat(CountryCodes.toIso(null)).isEmpty();
    }
}
