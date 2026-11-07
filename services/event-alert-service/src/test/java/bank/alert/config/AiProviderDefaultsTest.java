package bank.alert.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderDefaultsTest {

    @Test
    void defaultsToNovaProAsiaPacificFromSingapore() {
        AiProperties ai = new AiProperties(false, null, null, 0, 0, 0, 0,
                null, null);
        BedrockAiProperties bedrock = new BedrockAiProperties(null);

        assertThat(ai.model()).isEqualTo("apac.amazon.nova-pro-v1:0");
        assertThat(bedrock.region()).isEqualTo("ap-southeast-1");
    }
}
