package bank.alert.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCorePropertiesTest {

    @Test
    void withNoGatewayThereIsNoClientAndTokyoIsTheDefaultRegion() {
        AgentCoreProperties properties = new AgentCoreProperties("  ", null, null);

        assertThat(properties.gatewayUrl()).isNull();
        assertThat(properties.region()).isEqualTo("ap-northeast-1");
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aGatewayMustBeHttpsExceptOnLocalhostForTests() {
        assertThat(new AgentCoreProperties("https://gw.example.ap-northeast-1.amazonaws.com", null, null)
                .gatewayUrl()).startsWith("https://");
        assertThat(new AgentCoreProperties("http://127.0.0.1:8080", null, null).gatewayUrl()).isNotNull();
        assertThatThrownBy(() -> new AgentCoreProperties("http://gw.example.com", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentCoreProperties("ftp://gw.example.com", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTimeoutIsBounded() {
        assertThatThrownBy(() -> new AgentCoreProperties(null, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentCoreProperties(null, null, Duration.ofMinutes(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
