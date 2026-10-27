package bank.engagement.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class SecurityConfiguration {

    /**
     * Accepts only access tokens issued to the CRM's app client (ADR-0025), as
     * the other services do. Built from the pool's key URL, so nothing is
     * fetched until the first token arrives.
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${idp.cognito.app-client-id}") String appClientId) {
        if (!StringUtils.hasText(appClientId)) {
            throw new IllegalStateException("COGNITO_APP_CLIENT_ID is required");
        }
        String jwkSetUri = StringUtils.trimTrailingCharacter(issuer, '/') + "/.well-known/jwks.json";
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), new CognitoAccessTokenValidator(appClientId)));
        return decoder;
    }

    /**
     * Every route is for agents only. Admins oversee client contact through
     * the audit log, not here (agent-engagement plan §10).
     */
    @Bean
    SecurityFilterChain engagementSecurityFilterChain(
            HttpSecurity http, JwtRoleConverter roleConverter, ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/api/engagement/**").hasRole("AGENT")
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(objectMapper, response, HttpStatus.UNAUTHORIZED,
                                        "A valid bearer token is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(objectMapper, response, HttpStatus.FORBIDDEN, "Not permitted")))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter)))
                .build();
    }

    private static void writeError(ObjectMapper objectMapper, HttpServletResponse response,
            HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
