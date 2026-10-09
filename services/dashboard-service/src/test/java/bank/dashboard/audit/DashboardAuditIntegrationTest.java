package bank.dashboard.audit;

import bank.dashboard.service.DashboardCache;
import bank.dashboard.testsupport.DashboardTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DashboardTest
@AutoConfigureMockMvc
class DashboardAuditIntegrationTest {

    private static final String AGENT_SUB = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DashboardCache cache;

    @MockitoBean
    private AuditEmitter auditEmitter;

    @BeforeEach
    void clearCache() {
        cache.invalidateAll();
    }

    @Test
    void recordsAViewOfEveryEndpoint() throws Exception {
        mockMvc.perform(asAgent(get("/api/dashboard/overview")));
        mockMvc.perform(asAgent(get("/api/dashboard/portfolio")));
        mockMvc.perform(asAgent(get("/api/dashboard/transactions")));
        mockMvc.perform(asAgent(get("/api/dashboard/action-items")));

        ArgumentCaptor<DashboardAuditEvent> captured = ArgumentCaptor.forClass(DashboardAuditEvent.class);
        verify(auditEmitter, times(4)).emit(captured.capture());

        assertThat(captured.getAllValues()).extracting(DashboardAuditEvent::action)
                .containsExactly("VIEW_OVERVIEW", "VIEW_PORTFOLIO", "VIEW_TRANSACTIONS", "VIEW_ACTION_ITEMS");
        assertThat(captured.getAllValues()).allSatisfy(event -> {
            assertThat(event.actorId()).isEqualTo(AGENT_SUB);
            assertThat(event.sourceService()).isEqualTo("DASHBOARD");
            assertThat(event.payload().scope()).isEqualTo("AGENT");
        });
    }

    @Test
    void recordsACachedViewToo() throws Exception {
        mockMvc.perform(asAgent(get("/api/dashboard/overview")));
        mockMvc.perform(asAgent(get("/api/dashboard/overview")));

        ArgumentCaptor<DashboardAuditEvent> captured = ArgumentCaptor.forClass(DashboardAuditEvent.class);
        // Two views, two events. The audit question is who looked, which a
        // cache does not change -- so serving from cache must not make a view
        // invisible to the audit trail.
        verify(auditEmitter, times(2)).emit(captured.capture());

        assertThat(captured.getAllValues().get(0).payload().cacheHit()).isFalse();
        assertThat(captured.getAllValues().get(1).payload().cacheHit()).isTrue();
    }

    @Test
    void reportsTheCacheHitOnEveryEndpointNotOnlyOverview() throws Exception {
        // Each endpoint keys its own cache entry. A key the service never
        // writes would report cache_hit false forever without failing
        // anything, so every endpoint has to be asserted, not just one.
        String[] paths = {"/overview", "/portfolio", "/transactions", "/action-items"};
        for (String path : paths) {
            mockMvc.perform(asAgent(get("/api/dashboard" + path)));
            mockMvc.perform(asAgent(get("/api/dashboard" + path)));
        }

        ArgumentCaptor<DashboardAuditEvent> captured = ArgumentCaptor.forClass(DashboardAuditEvent.class);
        verify(auditEmitter, times(paths.length * 2)).emit(captured.capture());

        for (int i = 0; i < paths.length; i++) {
            assertThat(captured.getAllValues().get(i * 2).payload().cacheHit())
                    .describedAs("first call to %s is a miss", paths[i])
                    .isFalse();
            assertThat(captured.getAllValues().get(i * 2 + 1).payload().cacheHit())
                    .describedAs("second call to %s is a hit", paths[i])
                    .isTrue();
        }
    }

    @Test
    void recordsThePanelStatusesSoADegradedViewIsDistinguishable() throws Exception {
        mockMvc.perform(asAgent(get("/api/dashboard/overview")));

        ArgumentCaptor<DashboardAuditEvent> captured = ArgumentCaptor.forClass(DashboardAuditEvent.class);
        verify(auditEmitter).emit(captured.capture());

        DashboardViewPayload payload = captured.getValue().payload();
        assertThat(payload.panels()).containsEntry("clients", "UNAVAILABLE");
        assertThat(payload.resultCount()).isZero();
        assertThat(payload.windowFrom()).isNotBlank();
        assertThat(payload.windowTo()).isNotBlank();
    }

    @Test
    void doesNotRecordAViewThatWasRejectedBeforeItHappened() throws Exception {
        // A 400 for a bad window is not a view of anything, so there is
        // nothing to audit. Recording it would put noise in the trail.
        mockMvc.perform(asAgent(get("/api/dashboard/overview")
                        .param("from", "2026-09-20")
                        .param("to", "2026-01-01")))
                .andExpect(status().isBadRequest());

        verify(auditEmitter, times(0)).emit(any());
    }

    @Test
    void aFailingEmitterDoesNotFailTheRead() throws Exception {
        doThrow(new IllegalStateException("audit sink is down")).when(auditEmitter).emit(any());

        // 503 because every upstream is absent, not because auditing threw.
        // The point is that the caller still gets their response.
        mockMvc.perform(asAgent(get("/api/dashboard/overview")))
                .andExpect(status().isServiceUnavailable());
    }

    private MockHttpServletRequestBuilder asAgent(MockHttpServletRequestBuilder request) {
        return request.with(jwt()
                .jwt(j -> j.subject(AGENT_SUB))
                .authorities(new SimpleGrantedAuthority("ROLE_AGENT")));
    }
}
