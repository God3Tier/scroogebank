package bank.engagement.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Maps {@code cognito:groups} to roles. The principal's name is the {@code sub}. */
@Component
public class JwtRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<SimpleGrantedAuthority> authorities = groups(jwt).stream()
                .map(group -> new SimpleGrantedAuthority("ROLE_" + group.toUpperCase(Locale.ROOT)))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private static List<String> groups(Jwt jwt) {
        Object claim = jwt.getClaims().get("cognito:groups");
        if (!(claim instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
