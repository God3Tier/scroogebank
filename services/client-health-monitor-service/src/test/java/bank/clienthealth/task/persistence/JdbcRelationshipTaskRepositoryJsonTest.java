package bank.clienthealth.task.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.ClientHealthFinding.AnalysisWindow;
import bank.clienthealth.contract.ClientHealthFinding.PriorityInputs;
import bank.clienthealth.contract.ClientHealthFinding.RecurringCreditInterruptionEvidence;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalEvidence;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcRelationshipTaskRepositoryJsonTest {
    @Test
    void readsJacksonNumericInstantFromStoredFinding() throws Exception {
        var json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var repository = new JdbcRelationshipTaskRepository(
                mock(JdbcTemplate.class), mock(TransactionTemplate.class), json);
        Instant detectedAt = Instant.parse("2026-10-23T00:00:00Z");
        Instant baselineStart = detectedAt.minusSeconds(120L * 86_400);
        Instant comparisonStart = detectedAt.minusSeconds(30L * 86_400);
        var window = new SignalWindow(
                baselineStart, comparisonStart, comparisonStart, detectedAt.minusSeconds(60));
        var signal = new SignalEvidence<>("signal-1", "account-1",
                SignalType.RECURRING_CREDIT_INTERRUPTION, "rule-v1", Severity.HIGH, window,
                new RecurringCreditInterruptionEvidence(30, 3, 60), "a".repeat(64));
        var finding = new ClientHealthFinding("1", "finding-1", "client-1", "run-1", "rules-v1",
                new AnalysisWindow(baselineStart, detectedAt), List.of(signal),
                new PriorityInputs(Severity.HIGH, 1), "correlation-1", detectedAt);
        String stored = json.writeValueAsString(finding);

        assertThat(json.readTree(stored).path("detectedAt").isNumber()).isTrue();
        assertThat(repository.readFinding(stored)).isEqualTo(finding);
    }
}
