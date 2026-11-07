package bank.dashboard.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CognitoAccessTokenValidatorTest {

    private final CognitoAccessTokenValidator validator = new CognitoAccessTokenValidator("crm-app-client");

    @Test
    void acceptsAnAccessTokenForTheCrmAppClient() {
        assertThat(validator.validate(token(Map.of("token_use", "access", "client_id", "crm-app-client"))).hasErrors())
                .isFalse();
    }

    @Test
    void refusesAnotherAppClientsToken() {
        // For example the transaction import's client-credentials token (ADR-0037).
        assertThat(validator.validate(token(Map.of("token_use", "access", "client_id", "transaction-ingest"))).hasErrors())
                .isTrue();
    }

    @Test
    void refusesAnIdToken() {
        assertThat(validator.validate(token(Map.of("token_use", "id", "client_id", "crm-app-client"))).hasErrors())
                .isTrue();
    }

    @Test
    void refusesATokenWithoutAClientId() {
        assertThat(validator.validate(token(Map.of("token_use", "access"))).hasErrors()).isTrue();
    }

    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("sub-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
