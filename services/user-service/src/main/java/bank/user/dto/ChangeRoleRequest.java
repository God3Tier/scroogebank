package bank.user.dto;

import bank.user.domain.Role;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(
        @NotNull Role role
) {
}
