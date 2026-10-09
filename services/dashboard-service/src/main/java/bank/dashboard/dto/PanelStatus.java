package bank.dashboard.dto;

/**
 * How completely a panel resolved. Part of the API contract, not an internal
 * detail: a client renders per-panel state rather than assuming an HTTP 200
 * means every figure is present.
 *
 * <p>There are two states, not three. An earlier revision carried a
 * {@code PARTIAL} value that nothing ever produced -- a panel is resolved by a
 * single upstream call, so it either has its figures or it does not. Publishing
 * a state the server cannot emit obliges every client to write a branch that
 * can never be taken. A <em>response</em> can still be partial, in the sense
 * that some panels are OK and others are not; that is visible in the panel map
 * itself and needs no status of its own.
 */
public enum PanelStatus {

    /** Fully resolved. */
    OK,

    /** Not resolved. {@code data} is null and {@code reason} says why. */
    UNAVAILABLE
}
