package bank.clienthealth.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Accepts only Cognito access tokens issued to the CRM application client. */
final class CognitoAccessTokenValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error INVALID = new OAuth2Error(
            "invalid_token", "Expected an access token issued to this Cognito app client", null);

    private final String appClientId;

    CognitoAccessTokenValidator(String appClientId) {
        this.appClientId = appClientId;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        boolean valid = "access".equals(token.getClaimAsString("token_use"))
                && appClientId.equals(token.getClaimAsString("client_id"));
        return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(INVALID);
    }
}
