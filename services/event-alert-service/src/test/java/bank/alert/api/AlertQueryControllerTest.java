package bank.alert.api;

import bank.alert.domain.AlertStatus;
import bank.alert.security.JwtRoleConverter;
import bank.alert.security.SecurityConfiguration;
import bank.alert.service.AlertQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertQueryController.class)
@Import({SecurityConfiguration.class, JwtRoleConverter.class, AlertApiExceptionHandler.class})
class AlertQueryControllerTest {

    private static final UUID ALERT_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AlertQueryService queryService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void agentListUsesAuthenticatedSubjectAndFilters() throws Exception {
        AlertSummaryResponse alert = new AlertSummaryResponse(
                ALERT_ID, "syn-client-001", "SYN-SGX-001", "Synthetic event",
                AlertStatus.NEW, new BigDecimal("72.50"),
                Instant.parse("2026-09-27T00:01:00Z"), 0L);
        when(queryService.listAlerts(
                "syn-agent-001", false, AlertStatus.NEW,
                new BigDecimal("60.00"), null, 10))
                .thenReturn(new AlertPageResponse(List.of(alert), null));

        mockMvc.perform(get("/api/alerts")
                        .with(user("syn-agent-001").roles("AGENT"))
                        .queryParam("status", "NEW")
                        .queryParam("minimumScore", "60.00")
                        .queryParam("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(ALERT_ID.toString()))
                .andExpect(jsonPath("$.items[0].headline").value("Synthetic event"));

        verify(queryService).listAlerts(
                "syn-agent-001", false, AlertStatus.NEW,
                new BigDecimal("60.00"), null, 10);
    }

    @Test
    void adminGetsReadOnlyGlobalListAndUnknownRoleIsForbidden() throws Exception {
        when(queryService.listAlerts("syn-admin-001", true, null, null, null, 25))
                .thenReturn(new AlertPageResponse(List.of(), null));

        mockMvc.perform(get("/api/alerts")
                        .with(user("syn-admin-001").roles("ADMIN")))
                .andExpect(status().isOk());
        verify(queryService).listAlerts("syn-admin-001", true, null, null, null, 25);

        mockMvc.perform(get("/api/alerts")
                        .with(user("syn-auditor-001").roles("AUDITOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidPaginationParameters() throws Exception {
        mockMvc.perform(get("/api/alerts")
                        .with(user("syn-agent-001").roles("AGENT"))
                        .queryParam("minimumScore", "101")
                        .queryParam("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").exists());
    }
}
