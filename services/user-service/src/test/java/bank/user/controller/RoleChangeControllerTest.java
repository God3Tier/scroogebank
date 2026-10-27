package bank.user.controller;

import bank.user.audit.AuditEventPublisher;
import bank.user.domain.Role;
import bank.user.domain.User;
import bank.user.exception.ForbiddenOperationException;
import bank.user.security.JwtRoleConverter;
import bank.user.security.SecurityConfig;
import bank.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class})
class RoleChangeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserService userService;

    // Required by UserAuditInterceptor, which @WebMvcTest loads.
    @MockitoBean
    private AuditEventPublisher auditEventPublisher;

    // SecurityConfig's decoder fetches the issuer's OIDC metadata when created.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID id = UUID.randomUUID();

    @Test
    void adminChangesRole() throws Exception {
        User promoted = new User(id, "Alice", "Tan", "alice@scroogebank.com", Role.ADMIN, "sub-alice");
        // Set by Hibernate when the row is saved.
        ReflectionTestUtils.setField(promoted, "createdAt", Instant.parse("2026-09-01T00:00:00Z"));
        ReflectionTestUtils.setField(promoted, "updatedAt", Instant.parse("2026-09-02T00:00:00Z"));
        when(userService.changeRole(id, Role.ADMIN)).thenReturn(promoted);

        mvc.perform(patch("/api/users/{id}/role", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}")
                        .with(jwt().jwt(token -> token.subject("sub-admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        verify(auditEventPublisher).publish("ROLE_CHANGE", "sub-admin", "USER", id.toString(),
                "/api/users/{id}/role", "PATCH", 200, "ADMIN");
    }

    @Test
    void agentIsForbidden() throws Exception {
        mvc.perform(patch("/api/users/{id}/role", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AGENT"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userService);
        // jwt() names its subject "user". The denial must not be recorded as a success.
        verify(auditEventPublisher).publish("ROLE_CHANGE", "user", "USER", id.toString(),
                "/api/users/{id}/role", "PATCH", 403, null);
    }

    @Test
    void missingRoleIsBadRequest() throws Exception {
        mvc.perform(patch("/api/users/{id}/role", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(userService);
    }

    @Test
    void rootAdminIsForbidden() throws Exception {
        when(userService.changeRole(id, Role.AGENT))
                .thenThrow(new ForbiddenOperationException("Root admin's role cannot be changed"));

        mvc.perform(patch("/api/users/{id}/role", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"AGENT\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Root admin's role cannot be changed"));
    }
}
