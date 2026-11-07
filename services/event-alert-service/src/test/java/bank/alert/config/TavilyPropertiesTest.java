package bank.alert.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TavilyPropertiesTest {

    @Test
    void pinsTheOfficialEndpointAndRedactsTheKey() {
        TavilyProperties properties = new TavilyProperties(" tvly-secret ", null, null, 0);

        assertThat(properties.apiKey()).isEqualTo("tvly-secret");
        assertThat(properties.endpoint()).isEqualTo(URI.create("https://api.tavily.com/search"));
        assertThat(properties.toString()).contains("<redacted>").doesNotContain("tvly-secret");
    }

    @Test
    void refusesAKeyExfiltrationEndpointOrUnboundedResponse() {
        assertThatThrownBy(() -> new TavilyProperties("key", URI.create("https://evil.example/search"),
                Duration.ofSeconds(30), 262_144)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TavilyProperties("key", null,
                Duration.ofSeconds(30), 1_048_577)).isInstanceOf(IllegalArgumentException.class);
    }
}
