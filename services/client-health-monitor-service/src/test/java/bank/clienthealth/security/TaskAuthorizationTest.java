package bank.clienthealth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class TaskAuthorizationTest {
    private final TaskAuthorization authorization = new TaskAuthorization();

    @Test
    void agentCanReadAndMutate() {
        var agent = authentication("ROLE_AGENT");
        assertThat(authorization.canRead(agent)).isTrue();
        assertThat(authorization.canMutate(agent)).isTrue();
    }

    @Test
    void adminAndDualRoleTokensAreReadOnly() {
        var admin = authentication("ROLE_ADMIN");
        var dualRole = authentication("ROLE_AGENT", "ROLE_ADMIN");

        assertThat(authorization.canRead(admin)).isTrue();
        assertThat(authorization.canMutate(admin)).isFalse();
        assertThat(authorization.canRead(dualRole)).isTrue();
        assertThat(authorization.canMutate(dualRole)).isFalse();
    }

    @Test
    void unrelatedRoleCannotReadOrMutate() {
        var authentication = authentication("ROLE_AUDITOR");
        assertThat(authorization.canRead(authentication)).isFalse();
        assertThat(authorization.canMutate(authentication)).isFalse();
    }

    private static UsernamePasswordAuthenticationToken authentication(String... roles) {
        return UsernamePasswordAuthenticationToken.authenticated(
                "synthetic-subject", "n/a",
                List.of(roles).stream().map(SimpleGrantedAuthority::new).toList());
    }
}
