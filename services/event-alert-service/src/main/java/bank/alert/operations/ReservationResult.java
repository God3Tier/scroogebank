package bank.alert.operations;

public record ReservationResult(boolean allowed, String code, long remaining) {
    public static ReservationResult allowed(long remaining) {
        return new ReservationResult(true, "ALLOWED", remaining);
    }

    public static ReservationResult denied(String code, long remaining) {
        return new ReservationResult(false, code, Math.max(0, remaining));
    }
}
