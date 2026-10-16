package bank.alert.api;

import bank.alert.domain.AlertDecisionType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DecisionRequest(
        @NotNull AlertDecisionType decision,
        @Min(0) long expectedVersion,
        @Size(max = 500) String note) {
}
