package bank.clienthealth.task.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.clienthealth.task.port.DispositionPolicy.InvalidDispositionException;
import org.junit.jupiter.api.Test;

class BoundedDispositionPolicyTest {
    private final BoundedDispositionPolicy policy = new BoundedDispositionPolicy();

    @Test
    void acceptsOpaqueCodeAndBoundedNote() {
        assertThatCode(() -> policy.validate("TEAM_APPROVED_CODE", "x".repeat(500)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankOrOversizedValues() {
        assertThatThrownBy(() -> policy.validate(" ", null))
                .isInstanceOf(InvalidDispositionException.class);
        assertThatThrownBy(() -> policy.validate("x".repeat(129), null))
                .isInstanceOf(InvalidDispositionException.class);
        assertThatThrownBy(() -> policy.validate("VALID", "x".repeat(501)))
                .isInstanceOf(InvalidDispositionException.class);
    }
}
