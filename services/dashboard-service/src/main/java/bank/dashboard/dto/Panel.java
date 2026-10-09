package bank.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One panel of a dashboard response.
 *
 * @param status how completely it resolved
 * @param data   panel-specific figures, null when {@code status} is UNAVAILABLE
 * @param reason why it did not fully resolve, null when it did
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Panel(PanelStatus status, Object data, PanelReason reason) {

    public static Panel ok(Object data) {
        return new Panel(PanelStatus.OK, data, null);
    }

    public static Panel unavailable(PanelReason reason) {
        return new Panel(PanelStatus.UNAVAILABLE, null, reason);
    }

    public boolean isUnavailable() {
        return status == PanelStatus.UNAVAILABLE;
    }
}
