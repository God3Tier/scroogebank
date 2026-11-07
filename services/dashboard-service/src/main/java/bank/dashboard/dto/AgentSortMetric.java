package bank.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The metrics the per-agent table may be ordered by.
 *
 * <p>An enum rather than a free string because the constraint is a safety
 * control, not a convenience. ADR-0038 refuses to compute a composite agent
 * score, and records that "the default sort is an explicit metric (clients
 * onboarded, descending), never a derived rank". A {@code String} parameter is
 * the gap through which a {@code score} or {@code rank} value would arrive and
 * be honoured; a closed set is what makes that refusal enforceable rather than
 * merely stated.
 *
 * <p>Each value is a metric with a query definition in ADR-0038. The wire
 * values are camelCase because {@code docs/api/dashboard-api.yaml} fixes them
 * that way.
 *
 * <p>Deposits are a per-currency figure, so {@code bookedInitialDeposits}
 * ranks by the deposits in one named currency, given with it (ADR-0062).
 */
public enum AgentSortMetric {

    CLIENTS_ONBOARDED("clientsOnboarded"),
    VERIFICATION_RATE("verificationRate"),
    ACCOUNTS_OPENED("accountsOpened"),
    BOOKED_INITIAL_DEPOSITS("bookedInitialDeposits");

    private final String wireValue;

    AgentSortMetric(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /**
     * @throws IllegalArgumentException naming the accepted values. The
     *         rejected input is deliberately not echoed back.
     */
    public static AgentSortMetric fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(metric -> metric.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("must be one of " + accepted()));
    }

    public static String accepted() {
        return Arrays.stream(values()).map(AgentSortMetric::wireValue).collect(Collectors.joining(", "));
    }
}
