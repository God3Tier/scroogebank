package bank.user.controller;

import bank.user.audit.AuditEventPublisher;
import bank.user.domain.Role;
import bank.user.domain.User;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class})
class CurrentAdminControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserService userService;
    @MockitoBean AuditEventPublisher auditEventPublisher;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void resolvesRootAdminFromVerifiedSubject() throws Exception {
        User root = new User(UUID.randomUUID(), "Root", "Admin", "root@example.test", Role.ROOT_ADMIN, "root-sub");
        root.setDeletable(false);
        ReflectionTestUtils.setField(root, "createdAt", Instant.parse("2026-09-01T00:00:00Z"));
        ReflectionTestUtils.setField(root, "updatedAt", Instant.parse("2026-09-02T00:00:00Z"));
        when(userService.getCurrentAdmin("root-sub")).thenReturn(root);

        mvc.perform(get("/api/users/me").with(jwt().jwt(token -> token.subject("root-sub"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ROOT_ADMIN"))
                .andExpect(jsonPath("$.deletable").value(false));
        verify(userService).getCurrentAdmin("root-sub");
    }

    @Test
    void identifiesRegularAdminSeparately() throws Exception {
        User admin = new User(UUID.randomUUID(), "Regular", "Admin", "admin@example.test", Role.ADMIN, "admin-sub");
        ReflectionTestUtils.setField(admin, "createdAt", Instant.parse("2026-09-01T00:00:00Z"));
        ReflectionTestUtils.setField(admin, "updatedAt", Instant.parse("2026-09-02T00:00:00Z"));
        when(userService.getCurrentAdmin("admin-sub")).thenReturn(admin);

        mvc.perform(get("/api/users/me").with(jwt().jwt(token -> token.subject("admin-sub"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void agentCannotReadAdminIdentity() throws Exception {
        mvc.perform(get("/api/users/me").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AGENT"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userService);
    }
}
