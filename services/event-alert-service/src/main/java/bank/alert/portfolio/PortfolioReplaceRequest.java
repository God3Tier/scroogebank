package bank.alert.portfolio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A complete replacement of one account's recorded portfolio. */
public record PortfolioReplaceRequest(
        @Min(0) long expectedRevision,
        @NotBlank @Size(max = 64) String sourceLabel,
        @NotNull @Size(max = 100) List<@Valid @NotNull Position> positions) {

    public record Position(
            @NotBlank @Size(max = 32) String instrumentId,
            @NotNull BigDecimal quantity,
            @NotNull BigDecimal unitPrice,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String valuationCurrency,
            @NotNull BigDecimal fxToSgd,
            @NotNull Instant valuedAt) {
    }
}
