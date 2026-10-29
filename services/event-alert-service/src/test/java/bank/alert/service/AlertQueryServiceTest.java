package bank.alert.service;

import bank.alert.api.BriefingResponse;
import bank.alert.api.EvidenceResponse;
import bank.alert.api.ExplanationFactorResponse;
import bank.alert.api.AlertSummaryResponse;
import bank.alert.domain.AlertStatus;
import bank.alert.domain.ValuationFreshness;
import bank.alert.repository.AlertDecisionRepository;
import bank.alert.repository.AlertListRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertQueryServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-27T00:01:00Z");
    private static final UUID FIRST_ID = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID SECOND_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");

    private final AlertListRepository repository = mock(AlertListRepository.class);
    private final AlertDecisionRepository decisionRepository = mock(AlertDecisionRepository.class);
    private final AlertQueryService service = new AlertQueryService(repository, org.mockito.Mockito.mock(bank.alert.crm.CurrentOwnership.class), decisionRepository);

    @Test
    void returnsStableOpaqueCursorFromLastVisibleRow() {
        var first = alert(FIRST_ID);
        var second = alert(SECOND_ID);
        when(repository.findVisible("syn-agent-001", false, null, null, null, null, 2))
                .thenReturn(List.of(first, second));

        var page = service.listAlerts("syn-agent-001", false, null, null, null, 1);

        assertThat(page.items()).containsExactly(first);
        assertThat(page.nextCursor()).isNotBlank();

        service.listAlerts("syn-agent-001", false, null, null, page.nextCursor(), 1);
        verify(repository).findVisible(
                "syn-agent-001", false, null, null, CREATED_AT, FIRST_ID, 2);
    }

    @Test
    void rejectsMalformedCursorBeforeQueryingDatabase() {
        assertThatThrownBy(() ->
                service.listAlerts("syn-agent-001", false, null, null, "not-a-cursor", 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid alert cursor");
    }

    @Test
    void buildsDetailOnlyAfterRepositoryScopeCheck() {
        var summary = alert(FIRST_ID);
        var evidence = new EvidenceResponse(
                "Synthetic event", "https://example.invalid/event", "example.invalid",
                null, CREATED_AT);
        var briefing = new BriefingResponse(
                "Synthetic summary", true, "fixture-model", "prompt-v1", null);
        when(repository.findVisibleDetail(FIRST_ID, "syn-agent-001", false))
                .thenReturn(Optional.of(new AlertListRepository.DetailRow(
                        summary, new BigDecimal("2500.0000"), new BigDecimal("25.0000"),
                        CREATED_AT, ValuationFreshness.FRESH, evidence, briefing)));
        when(repository.findFactors(FIRST_ID)).thenReturn(List.of(
                new ExplanationFactorResponse(
                        "EXPOSURE", new BigDecimal("35.00"), "Synthetic exposure factor")));

        var detail = service.getAlert(FIRST_ID, "syn-agent-001", false);

        assertThat(detail.id()).isEqualTo(FIRST_ID);
        assertThat(detail.exposure().amountSgd()).isEqualTo("2500.00");
        assertThat(detail.evidence()).containsExactly(evidence);
        assertThat(detail.briefing()).isEqualTo(briefing);
        assertThat(detail.latestDecision()).isNull();
    }

    @Test
    void hidesDetailOutsideRepositoryScope() {
        when(repository.findVisibleDetail(FIRST_ID, "syn-agent-999", false))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAlert(FIRST_ID, "syn-agent-999", false))
                .isInstanceOf(AlertNotFoundException.class);
    }

    private static AlertSummaryResponse alert(UUID id) {
        return new AlertSummaryResponse(
                id, "syn-client-001", "SYN-SGX-001", "Synthetic event",
                AlertStatus.NEW, new BigDecimal("72.50"), CREATED_AT, 0L);
    }
}
