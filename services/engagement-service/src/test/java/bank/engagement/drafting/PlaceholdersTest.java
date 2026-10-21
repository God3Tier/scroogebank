package bank.engagement.drafting;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholdersTest {

    @Test
    void fillsTheAllowedPlaceholders() {
        String filled = Placeholders.fill("Dear {{client_first_name}}, from {{ agent_name }}",
                Map.of("client_first_name", "Synthetic", "agent_name", "Agent Example"));

        assertThat(filled).isEqualTo("Dear Synthetic, from Agent Example");
        assertThat(Placeholders.unresolved(filled)).isFalse();
    }

    @Test
    void leavesAPlaceholderWithoutAValue() {
        Map<String, String> values = new HashMap<>();
        values.put("client_first_name", "Synthetic");
        values.put("agent_name", null);

        String filled = Placeholders.fill("Dear {{client_first_name}}, from {{agent_name}}", values);

        assertThat(filled).isEqualTo("Dear Synthetic, from {{agent_name}}");
        assertThat(Placeholders.unresolved(filled)).isTrue();
    }

    @Test
    void valuesAreInsertedLiterally() {
        assertThat(Placeholders.fill("Hi {{client_first_name}}", Map.of("client_first_name", "$1 \\ name")))
                .isEqualTo("Hi $1 \\ name");
    }

    @Test
    void onlyTheTwoPlaceholdersAreAllowed() {
        assertThat(Placeholders.onlyAllowed("Hi {{client_first_name}} from {{agent_name}}")).isTrue();
        assertThat(Placeholders.onlyAllowed("Hi {{client_last_name}}")).isFalse();
        assertThat(Placeholders.onlyAllowed("Hi }} there")).isFalse();
    }

    @Test
    void recencyIsBanded() {
        Instant now = Instant.parse("2026-10-21T02:00:00Z");
        assertThat(MeetingRecency.of(Optional.empty(), now)).isEqualTo(MeetingRecency.NONE);
        assertThat(MeetingRecency.of(Optional.of(now.minusSeconds(86_400 * 10)), now))
                .isEqualTo(MeetingRecency.UNDER_1_MONTH);
        assertThat(MeetingRecency.of(Optional.of(now.minusSeconds(86_400 * 60)), now).label())
                .isEqualTo("1_TO_3_MONTHS");
        assertThat(MeetingRecency.of(Optional.of(now.minusSeconds(86_400 * 120)), now))
                .isEqualTo(MeetingRecency.OVER_3_MONTHS);
    }
}
