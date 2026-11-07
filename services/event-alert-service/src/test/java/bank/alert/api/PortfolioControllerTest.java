package bank.alert.api;

import bank.alert.crm.DirectoryUnavailableException;
import bank.alert.portfolio.PortfolioApiException;
import bank.alert.portfolio.PortfolioController;
import bank.alert.portfolio.PortfolioResponse;
import bank.alert.portfolio.PortfolioService;
import bank.alert.security.JwtRoleConverter;
import bank.alert.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PortfolioController.class)
@TestPropertySource(properties = "alert.holdings.mode=database")
@Import({SecurityConfiguration.class, JwtRoleConverter.class, AlertApiExceptionHandler.class})
class PortfolioControllerTest {

    private static final UUID ACCOUNT = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final String BODY = """
            {"expectedRevision":0,"sourceLabel":"manual-entry","positions":[
              {"instrumentId":"SYN-SGX-001","quantity":10,"unitPrice":1.5,"valuationCurrency":"SGD",
               "fxToSgd":1,"valuedAt":"2026-10-30T01:00:00Z"}]}
            """;

    @Autowired MockMvc mockMvc;
    @MockBean PortfolioService service;
    @MockBean JwtDecoder jwtDecoder;

    private static PortfolioResponse response() {
        return new PortfolioResponse(ACCOUNT, UUID.randomUUID(), 1, "manual-entry",
                Instant.parse("2026-10-30T02:00:00Z"), new BigDecimal("15.0000"), List.of());
    }

    @Test
    void theAgentComesFromTheTokenAndNeverFromTheRequest() throws Exception {
        when(service.replace(eq(ACCOUNT), eq("agent-a"), any(), eq("idem-key-0001"), any(), any())).thenReturn(response());

        mockMvc.perform(put("/api/alert-portfolios/{id}", ACCOUNT)
                        .with(jwt().jwt(token -> token.subject("agent-a").tokenValue("agent-a-token")).authorities(new SimpleGrantedAuthority("ROLE_AGENT")))
                        .header("Idempotency-Key", "idem-key-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));

        verify(service).replace(eq(ACCOUNT), eq("agent-a"), eq("agent-a-token"), eq("idem-key-0001"), any(), any());
    }

    @Test
    void adminsReadButCannotWriteAndAnonymousIsRefused() throws Exception {
        when(service.get(ACCOUNT, "admin-1", true)).thenReturn(response());

        mockMvc.perform(get("/api/alert-portfolios/{id}", ACCOUNT).with(user("admin-1").roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/alert-portfolios/{id}", ACCOUNT).with(user("admin-1").roles("ADMIN"))
                        .header("Idempotency-Key", "idem-key-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/alert-portfolios/{id}", ACCOUNT)).andExpect(status().isUnauthorized());
        verify(service, never()).replace(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsMalformedRequestsBeforeTheyReachTheService() throws Exception {
        mockMvc.perform(put("/api/alert-portfolios/{id}", ACCOUNT).with(jwt().jwt(token -> token.subject("agent-a").tokenValue("agent-a-token")).authorities(new SimpleGrantedAuthority("ROLE_AGENT")))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest()); // no idempotency key
        mockMvc.perform(put("/api/alert-portfolios/{id}", ACCOUNT).with(jwt().jwt(token -> token.subject("agent-a").tokenValue("agent-a-token")).authorities(new SimpleGrantedAuthority("ROLE_AGENT")))
                        .header("Idempotency-Key", "idem-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"SGD\"", "\"sgd\"")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/alert-portfolios/not-a-uuid").with(jwt().jwt(token -> token.subject("agent-a").tokenValue("agent-a-token")).authorities(new SimpleGrantedAuthority("ROLE_AGENT")))
                        .header("Idempotency-Key", "idem-key-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verify(service, never()).replace(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mapsDomainRefusalsAndAnUnreachableCrm() throws Exception {
        when(service.replace(any(), any(), any(), any(), any(), any()))
                .thenThrow(new PortfolioApiException(HttpStatus.CONFLICT, "expected portfolio revision is stale"))
                .thenThrow(new PortfolioApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unsupported instrument in portfolio"))
                .thenThrow(new DirectoryUnavailableException("down"));
        var request = put("/api/alert-portfolios/{id}", ACCOUNT).with(jwt().jwt(token -> token.subject("agent-a").tokenValue("agent-a-token")).authorities(new SimpleGrantedAuthority("ROLE_AGENT")))
                .header("Idempotency-Key", "idem-key-0001")
                .contentType(MediaType.APPLICATION_JSON).content(BODY);

        mockMvc.perform(request).andExpect(status().isConflict());
        mockMvc.perform(request).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("unsupported instrument in portfolio"));
        mockMvc.perform(request).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
    }
}
