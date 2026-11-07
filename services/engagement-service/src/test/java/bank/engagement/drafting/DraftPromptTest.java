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

    /**
     * v4 names the stock phrases the v3 drafts all shared, the everyday words
     * the screen still blocks (Nova Lite's "offer" failed health check-ins),
     * and how to write a line break in JSON.
     */
    @ParameterizedTest
    @EnumSource(DraftPurpose.class)
    void everyPurposeIsToldWhatMadeDraftsGenericOrRejected(DraftPurpose purpose) {
        assertThat(DraftPrompt.system(purpose))
                .contains("I hope this message finds you well", "timeSinceLastMeeting", "upcomingPublicHolidays")
                .contains("offer, interest, return")
                .contains("write each line break as \\n, never as \\\\n");
    }
}
