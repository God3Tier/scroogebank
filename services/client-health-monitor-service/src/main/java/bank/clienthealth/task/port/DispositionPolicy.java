package bank.clienthealth.task.port;

@FunctionalInterface
public interface DispositionPolicy {
    void validate(String code, String note);
}
