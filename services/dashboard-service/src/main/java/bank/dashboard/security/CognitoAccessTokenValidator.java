package bank.dashboard.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Accepts a Cognito access token issued to the CRM's app client, and nothing
 * else: not an ID token, and not a token issued to another app client in the
 * same pool. Cognito access tokens carry no {@code aud}, so {@code client_id}
 * is the claim that says who the token is for.
 */
@RequiredArgsConstructor
class CognitoAccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID = new OAuth2Error(
            "invalid_token", "Expected an access token issued to this Cognito app client", null);

    private final String appClientId;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        boolean valid = "access".equals(token.getClaimAsString("token_use"))
                && appClientId.equals(token.getClaimAsString("client_id"));
        return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(INVALID);
    }
}
