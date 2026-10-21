package bank.engagement.drafting;

import bank.engagement.holidays.HolidayView;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DraftGeneratorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final HolidayView DEEPAVALI =
            new HolidayView(UUID.randomUUID(), "SG", LocalDate.of(2026, 11, 8), "Deepavali", false);

    private final DraftModelGateway model = mock(DraftModelGateway.class);
    private final AiUsageService usage = mock(AiUsageService.class);

    private DraftGenerator generator(boolean enabled) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("model", model);
        return new DraftGenerator(new AiProperties(enabled, null, null, null, null, null, null, null), usage,
                beans.getBeanProvider(DraftModelGateway.class), new DraftSafetyScreen(new ObjectMapper()),
                new ObjectMapper(), new SimpleMeterRegistry());
    }

    @Test
    void theKillSwitchGivesTheTemplateWithoutTouchingTheModelOrTheCaps() {
        GeneratedDraft draft = generator(false).generate("agent", DraftPurpose.HOLIDAY_GREETING, DraftTone.FORMAL,
                List.of(DEEPAVALI), MeetingRecency.NONE, TODAY);

        assertThat(draft.source()).isEqualTo(DraftSource.TEMPLATE);
        assertThat(draft.sourceReason()).isEqualTo("ai_disabled");
        assertThat(draft.subject()).isEqualTo("Deepavali greetings, {{client_first_name}}");
        assertThat(draft.body()).contains("With Deepavali coming up on 8 November");
        assertThat(draft.holidayId()).isEqualTo(DEEPAVALI.id());
        assertThat(draft.promptVersion()).isEqualTo("templates-v1");
        assertThat(draft.modelId()).isNull();
        verifyNoInteractions(model, usage);
    }

    @Test
    void aTemplateForANonHolidayPurposeNamesNoHoliday() {
        GeneratedDraft draft = generator(false).generate("agent", DraftPurpose.CHECK_IN, DraftTone.WARM,
                List.of(DEEPAVALI), MeetingRecency.OVER_3_MONTHS, TODAY);

        assertThat(draft.holidayId()).isNull();
        verify(model, never()).invoke(anyString(), anyString());
    }
}
