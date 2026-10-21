package bank.alert.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.UUID;

// A worker runs without a web server and has no requests to secure.
@Configuration
@ConditionalOnWebApplication
@EnableMethodSecurity
public class SecurityConfiguration {

    /**
     * Accepts only access tokens issued to the CRM's app client (ADR-0005,
     * ADR-0025), as user-service and dashboard-service do. Checking the issuer
     * alone would also let through an ID token, which carries the same
     * {@code cognito:groups}, or a token from another app client in the pool.
     *
     * <p>Built from the pool's key URL, which Cognito publishes at a fixed path
     * under the issuer, so nothing is fetched until the first token arrives.
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

    @Bean
    SecurityFilterChain alertSecurityFilterChain(
            HttpSecurity http,
            JwtRoleConverter roleConverter,
            ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeProblem(objectMapper, response, 401,
                                        "Authentication required", "A valid bearer token is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeProblem(objectMapper, response, 403,
                                        "Access denied", "The authenticated role cannot perform this operation")))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter)))
                .build();
    }

    private static void writeProblem(
            ObjectMapper objectMapper,
            HttpServletResponse response,
            int status,
            String title,
            String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ObjectNode problem = objectMapper.createObjectNode();
        problem.put("type", "urn:scrooge:event-alerts:" + status);
        problem.put("title", title);
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("correlationId", UUID.randomUUID().toString());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
