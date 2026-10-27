package bank.clienthealth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class CognitoAccessTokenValidatorTest {
    private final CognitoAccessTokenValidator validator =
            new CognitoAccessTokenValidator("crm-app-client");

    @Test
    void acceptsOnlyAccessTokensForTheConfiguredClient() {
        assertThat(validator.validate(token("access", "crm-app-client")).hasErrors()).isFalse();
        assertThat(validator.validate(token("id", "crm-app-client")).hasErrors()).isTrue();
        assertThat(validator.validate(token("access", "another-client")).hasErrors()).isTrue();
    }

    private static Jwt token(String use, String clientId) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("synthetic-agent")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(300))
                .claims(claims -> claims.putAll(Map.of("token_use", use, "client_id", clientId)))
                .build();
    }
}
