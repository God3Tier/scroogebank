package bank.clienthealth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtRoleConverterTest {
    @Test
    void mapsStringGroupsAndUsesTheVerifiedSubjectAsPrincipal() {
        Jwt jwt = new Jwt("token", Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", "synthetic-agent", "cognito:groups", List.of("Agent", 42, "Admin")));

        var authentication = new JwtRoleConverter().convert(jwt);

        assertThat(authentication.getName()).isEqualTo("synthetic-agent");
        assertThat(authentication.getAuthorities()).extracting("authority")
                .containsExactly("ROLE_AGENT", "ROLE_ADMIN");
    }
}
