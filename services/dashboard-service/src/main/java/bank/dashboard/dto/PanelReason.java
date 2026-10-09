package bank.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Why a panel did not fully resolve. Machine-readable, so a client can tell
 * "this service is not wired up" from "this service is having a bad
 * day" without parsing prose.
 */
public enum PanelReason {

    /**
     * No base URL is configured for the upstream service, as when running the
     * dashboard on its own. It travels the same code path as a genuine
     * outage, so a panel lights up as soon as its service is wired in.
     */
    UPSTREAM_NOT_DEPLOYED("upstream_not_deployed"),

    /** The upstream did not answer inside its budget. */
    UPSTREAM_TIMEOUT("upstream_timeout"),

    /** The upstream answered with an error or something unparseable. */
    UPSTREAM_ERROR("upstream_error"),

    /**
     * The upstream refused the caller. Not a fault: it is the agent path
     * working correctly against an admin-only figure.
     */
    UPSTREAM_FORBIDDEN("upstream_forbidden"),

    /**
     * The accounts opened in the window use more than one currency, so
     * account-service will not add their deposits into one figure (ADR-0038).
     */
    MIXED_CURRENCIES("mixed_currencies"),

    /**
     * The team comparison covers only the last 7, 30 or 90 days, ending
     * today, and the requested window is another one (ADR-0038).
     */
    UNSUPPORTED_WINDOW("unsupported_window");

    private final String wireValue;

    PanelReason(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
