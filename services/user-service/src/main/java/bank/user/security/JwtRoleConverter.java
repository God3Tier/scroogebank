package bank.user.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps Cognito group membership (the "cognito:groups" claim) to Spring
 * authorities. Group membership is the single source of role truth.
 */
@Component
public class JwtRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractGroups(jwt).stream()
                .map(group -> new SimpleGrantedAuthority("ROLE_" + group.toUpperCase()))
                .collect(Collectors.toList());
        return new JwtAuthenticationToken(jwt, authorities);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractGroups(Jwt jwt) {
        Object groups = jwt.getClaims().get("cognito:groups");
        return groups instanceof List<?> list ? (List<String>) list : List.of();
    }
}
