package bank.dashboard.exception;

import bank.dashboard.dto.PanelReason;

/**
 * An upstream could not supply a figure.
 *
 * <p>Deliberately not handled by {@code GlobalExceptionHandler}: it is caught
 * by the panel assembler and turned into one unavailable panel, leaving the
 * rest of the response intact. An upstream being down degrades a panel, not
 * the page.
 */
public class UpstreamUnavailableException extends RuntimeException {

    private final transient PanelReason reason;

    public UpstreamUnavailableException(PanelReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PanelReason reason() {
        return reason;
    }
}
