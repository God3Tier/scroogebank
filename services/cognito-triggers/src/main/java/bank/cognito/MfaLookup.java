package bank.cognito;

/** Tells whether a user has a TOTP authenticator enrolled, and throws if it cannot tell. */
@FunctionalInterface
interface MfaLookup {
    boolean hasSoftwareToken(String userPoolId, String userName);
}
