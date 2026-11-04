package bank.alert.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddSourceDomainRequest(@NotBlank @Size(max = 300) String domain) {
}
