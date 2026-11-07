package bank.alert.service;

/** Whether a domain name exists on the internet. Kept apart from the policy so tests need no network. */
public interface DomainLookup {

    /** False only when the name is known not to exist; a lookup that cannot be completed is not a "no". */
    boolean exists(String domain);
}
