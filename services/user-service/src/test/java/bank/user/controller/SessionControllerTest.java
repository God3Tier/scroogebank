package bank.user.controller;

import bank.user.audit.AuditEventPublisher;
import bank.user.domain.Role;
import bank.user.domain.User;
import bank.user.exception.NotFoundException;
import bank.user.security.JwtRoleConverter;
import bank.user.security.SecurityConfig;
import bank.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SessionController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class})
class SessionControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserService userService;

    // UserAuditInterceptor, which @WebMvcTest loads, publishes through this.
    @MockitoBean
    private AuditEventPublisher auditEventPublisher;

    // SecurityConfig's decoder fetches the issuer's OIDC metadata when created.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID id = UUID.randomUUID();

    @Test
    void agentLogoutIsAuditedAgainstTheirOwnUser() throws Exception {
        User agent = new User(id, "Alice", "Tan", "alice@scroogebank.com", Role.AGENT, "sub-alice");
        when(userService.getUserByIdpSub("sub-alice")).thenReturn(agent);

        mvc.perform(post("/api/users/me/logout")
                        .with(jwt().jwt(token -> token.subject("sub-alice"))
                                .authorities(new SimpleGrantedAuthority("ROLE_AGENT"))))
                .andExpect(status().isNoContent());

        verify(auditEventPublisher).publish("LOGOUT", "sub-alice", id.toString(),
                "/api/users/me/logout", "POST", 204, "AGENT");
    }

    @Test
    void callerWithNoUserIsNotFoundAndAuditedAsFailed() throws Exception {
        when(userService.getUserByIdpSub("sub-unknown")).thenThrow(new NotFoundException("User not found"));

        mvc.perform(post("/api/users/me/logout")
                        .with(jwt().jwt(token -> token.subject("sub-unknown"))
                                .authorities(new SimpleGrantedAuthority("ROLE_AGENT"))))
                .andExpect(status().isNotFound());

        verify(auditEventPublisher).publish("LOGOUT", "sub-unknown", null,
                "/api/users/me/logout", "POST", 404, null);
    }

    @Test
    void unauthenticatedIsRejectedAndNotAudited() throws Exception {
        mvc.perform(post("/api/users/me/logout"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService, auditEventPublisher);
    }
}
