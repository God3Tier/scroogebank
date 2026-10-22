package bank.engagement.testsupport;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Synthetic callers. The subs are made up, as are all test data. */
public final class Tokens {

    public static final String AGENT = "11111111-1111-4111-8111-111111111111";
    public static final String OTHER_AGENT = "22222222-2222-4222-8222-222222222222";
    public static final String ADMIN = "33333333-3333-4333-8333-333333333333";

    private Tokens() {
    }

    public static RequestPostProcessor agent() {
        return agent(AGENT);
    }

    public static RequestPostProcessor agent(String sub) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(sub).tokenValue("token-" + sub))
                .authorities(new SimpleGrantedAuthority("ROLE_AGENT"));
    }

    public static RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(ADMIN))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
