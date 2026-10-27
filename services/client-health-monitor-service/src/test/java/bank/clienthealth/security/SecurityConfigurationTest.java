package bank.clienthealth.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = SecurityProbeController.class,
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.test/pool",
            "idp.cognito.app-client-id=crm-app-client"
        })
@Import({SecurityConfiguration.class, JwtRoleConverter.class, TaskAuthorization.class})
class SecurityConfigurationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedRequestReturnsProblemJson() throws Exception {
        mockMvc.perform(get("/probe/read"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void agentCanReadAndMutate() throws Exception {
        mockMvc.perform(get("/probe/read").with(role("ROLE_AGENT")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/probe/mutate").with(role("ROLE_AGENT")))
                .andExpect(status().isOk());
    }

    @Test
    void adminAndDualRoleTokensCannotMutate() throws Exception {
        mockMvc.perform(get("/probe/read").with(role("ROLE_ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/probe/mutate").with(role("ROLE_ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get("/probe/mutate").with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_AGENT"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor role(String role) {
        return jwt().jwt(token -> token.subject("synthetic-subject"))
                .authorities(new SimpleGrantedAuthority(role));
    }

}
