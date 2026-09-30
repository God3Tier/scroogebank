package bank.alert.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtRoleConverterTest {

    private final JwtRoleConverter converter = new JwtRoleConverter();

    @Test
    void mapsOnlyStringCognitoGroupsAndUsesSubjectAsPrincipal() {
        Jwt jwt = new Jwt(
                "token", Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", "syn-agent-001", "cognito:groups", List.of("Agent", 42, "Admin")));

        var authentication = converter.convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("syn-agent-001");
        assertThat(authentication.getAuthorities()).extracting("authority")
                .containsExactly("ROLE_AGENT", "ROLE_ADMIN");
    }
}
