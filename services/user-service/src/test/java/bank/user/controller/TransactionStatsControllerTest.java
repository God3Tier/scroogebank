package bank.user.controller;

import bank.user.audit.AuditEventPublisher;
import bank.user.security.JwtRoleConverter;
import bank.user.security.SecurityConfig;
import bank.user.service.TransactionStatsProxy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real bearer-token filter and role mapping run here: only the decoder is
 * replaced, so each token's Cognito groups decide the caller's scope.
 */
@WebMvcTest(TransactionStatsController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class})
class TransactionStatsControllerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TransactionStatsProxy proxy;

    // Required by UserAuditInterceptor, which @WebMvcTest loads.
    @MockitoBean
    private AuditEventPublisher auditEventPublisher;

    // SecurityConfig's decoder fetches the issuer's OIDC metadata when created.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode("agent-token")).thenReturn(token("agent-sub", List.of("AGENT")));
        when(jwtDecoder.decode("admin-token")).thenReturn(token("admin-sub", List.of("ADMIN")));
        when(jwtDecoder.decode("no-role-token")).thenReturn(token("someone", List.of()));
        when(proxy.getStats(any(), any(), anyBoolean(), anyString())).thenReturn(ResponseEntity.ok().build());
    }

    @Test
    void anAgentIsScopedAsAnAgentWhateverTheParametersSay() throws Exception {
        when(proxy.getStats(any(), any(), anyBoolean(), anyString()))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree("{\"total\":3}")));

        mvc.perform(get("/aggregates/transactions/stats")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .param("all", "true").param("agentId", "someone-else")
                        .header("Authorization", "Bearer agent-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));

        verify(proxy).getStats(FROM, TO, false, "Bearer agent-token");
        verifyNoInteractions(auditEventPublisher);
    }

    @Test
    void anAdminIsScopedAsAnAdmin() throws Exception {
        mvc.perform(get("/aggregates/transactions/stats")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());

        verify(proxy).getStats(FROM, TO, true, "Bearer admin-token");
    }

    @Test
    void aCallerWithoutACrmRoleIsRefused() throws Exception {
        mvc.perform(get("/aggregates/transactions/stats")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .header("Authorization", "Bearer no-role-token"))
                .andExpect(status().isForbidden());

        verify(proxy, never()).getStats(any(), any(), anyBoolean(), anyString());
    }

    @Test
    void aWindowThatIsNotADateIsABadRequest() throws Exception {
        mvc.perform(get("/aggregates/transactions/stats")
                        .param("from", "last-month").param("to", "2026-09-30")
                        .header("Authorization", "Bearer agent-token"))
                .andExpect(status().isBadRequest());

        verify(proxy, never()).getStats(any(), any(), anyBoolean(), anyString());
    }

    @Test
    void anUnauthenticatedCallerIsRefused() throws Exception {
        mvc.perform(get("/aggregates/transactions/stats").param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isUnauthorized());

        verify(proxy, never()).getStats(any(), any(), anyBoolean(), anyString());
    }

    private static Jwt token(String subject, List<String> groups) {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject(subject)
                .claim("cognito:groups", groups)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
