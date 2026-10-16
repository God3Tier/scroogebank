package bank.dashboard.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cognito group membership is the single source of role truth across the CRM,
 * so this mapping must stay identical to user-service's. A divergence here
 * would let a token grant different authority depending on which service
 * received it.
 */
class JwtRoleConverterTest {

    private final JwtRoleConverter converter = new JwtRoleConverter();

    @Test
    void mapsCognitoGroupsToUppercasedRoleAuthorities() {
        var authorities = converter.convert(jwtWith(Map.of("cognito:groups", List.of("admin"))))
                .getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(authorities).containsExactly("ROLE_ADMIN");
    }

    @Test
    void grantsNoAuthorityWhenTheClaimIsAbsent() {
        // A token from outside the pool's groups must not become privileged by
        // default.
        assertThat(converter.convert(jwtWith(Map.of())).getAuthorities()).isEmpty();
    }

    @Test
    void ignoresAClaimThatIsNotAList() {
        assertThat(converter.convert(jwtWith(Map.of("cognito:groups", "admin"))).getAuthorities()).isEmpty();
    }

    @Test
    void carriesEveryGroupThrough() {
        var authorities = converter.convert(jwtWith(Map.of("cognito:groups", List.of("admin", "agent"))))
                .getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(authorities).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_AGENT");
    }

    private Jwt jwtWith(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("11111111-1111-1111-1111-111111111111")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
