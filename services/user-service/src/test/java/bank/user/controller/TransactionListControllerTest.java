package bank.user.controller;

import bank.user.audit.AuditEventPublisher;
import bank.user.security.JwtRoleConverter;
import bank.user.security.SecurityConfig;
import bank.user.service.TransactionProxy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionListController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class})
class TransactionListControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TransactionProxy proxy;

    // UserAuditInterceptor, which @WebMvcTest loads, publishes through this.
    @MockitoBean
    private AuditEventPublisher auditEventPublisher;

    // SecurityConfig's decoder fetches the issuer's OIDC metadata when created.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void anAdminGetsThePageAndTheReadIsAuditedWithoutAResourceId() throws Exception {
        when(proxy.listTransactions("abc", 50, "Pending")).thenReturn(ResponseEntity.ok(
                new ObjectMapper().readTree("{\"content\":[],\"nextCursor\":null}")));

        mvc.perform(get("/api/transactions").with(admin())
                        .param("cursor", "abc").param("size", "50").param("status", "Pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        verify(proxy).listTransactions("abc", 50, "Pending");
        verify(auditEventPublisher).publish("READ", "sub-admin", "TRANSACTION", null,
                "/api/transactions", "GET", 200, null);
    }

    @Test
    void parametersAreOptional() throws Exception {
        when(proxy.listTransactions(null, null, null)).thenReturn(ResponseEntity.ok().build());

        mvc.perform(get("/api/transactions").with(admin()))
                .andExpect(status().isOk());

        verify(proxy).listTransactions(null, null, null);
    }

    @Test
    void anAgentIsForbiddenAndTheAttemptIsAudited() throws Exception {
        mvc.perform(get("/api/transactions")
                        .with(jwt().jwt(token -> token.subject("sub-agent"))
                                .authorities(new SimpleGrantedAuthority("ROLE_AGENT"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(proxy);
        verify(auditEventPublisher).publish("READ", "sub-agent", "TRANSACTION", null,
                "/api/transactions", "GET", 403, null);
    }

    @Test
    void anUnauthenticatedCallerIsRefused() throws Exception {
        mvc.perform(get("/api/transactions"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(proxy);
    }

    @Test
    void aSizeThatIsNotANumberIsABadRequest() throws Exception {
        mvc.perform(get("/api/transactions").with(admin()).param("size", "many"))
                .andExpect(status().isBadRequest());

        verify(proxy, never()).listTransactions(any(), any(), any());
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("sub-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
