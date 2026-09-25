package bank.user.controller;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

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

    private final UUID id = UUID.randomUUID();

    @Test
    void adminChangesRole() throws Exception {
        User promoted = new User(id, "Alice", "Tan", "alice@scroogebank.com", Role.ADMIN, "sub-alice");
        when(userService.changeRole(id, Role.ADMIN)).thenReturn(promoted);

        mvc.perform(patch("/api/users/{id}/role", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void agentIsForbidden() throws Exception {
        mvc.perform(patch("/api/users/{id}/role", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AGENT"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userService);
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
