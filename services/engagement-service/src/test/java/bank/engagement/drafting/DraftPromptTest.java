package bank.engagement.drafting;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class DraftPromptTest {

    /** Naming the slot to a purpose without one made the model write it, and the screen reject the draft. */
    @ParameterizedTest
    @EnumSource(DraftPurpose.class)
    void onlyAPurposeWithADetailsSlotIsToldAboutIt(DraftPurpose purpose) {
        String system = DraftPrompt.system(purpose);

        assertThat(system.contains(Placeholders.AGENT_DETAILS_TOKEN)).isEqualTo(purpose.hasDetailsSlot());
        assertThat(system)
                .contains(Placeholders.CLIENT_FIRST_NAME_TOKEN, Placeholders.AGENT_NAME_TOKEN)
                .contains("Reply with only a JSON object and nothing else");
    }
}
