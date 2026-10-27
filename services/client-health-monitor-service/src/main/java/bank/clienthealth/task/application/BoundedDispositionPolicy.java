package bank.clienthealth.task.application;

import bank.clienthealth.task.port.DispositionPolicy;

/** MVP policy while disposition codes remain opaque and owner-managed. */
public final class BoundedDispositionPolicy implements DispositionPolicy {
    private static final int MAX_CODE_LENGTH = 128;
    private static final int MAX_NOTE_LENGTH = 500;

    @Override
    public void validate(String code, String note) {
        if (code == null || code.isBlank() || code.length() > MAX_CODE_LENGTH) {
            throw new InvalidDispositionException("Disposition code must be between 1 and 128 characters");
        }
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new InvalidDispositionException("Disposition note must be at most 500 characters");
        }
    }
}
