package bank.clienthealth.task.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.security.JwtRoleConverter;
import bank.clienthealth.security.SecurityConfiguration;
import bank.clienthealth.security.TaskAuthorization;
import bank.clienthealth.task.application.RelationshipTaskService;
import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.domain.RelationshipTask.AssignmentSnapshot;
import bank.clienthealth.task.domain.RelationshipTask.OptimisticLockException;
import bank.clienthealth.task.domain.RelationshipTask.TaskAccessException;
import bank.clienthealth.task.port.DispositionPolicy.InvalidDispositionException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = RelationshipTaskActionController.class,
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.test/pool",
            "idp.cognito.app-client-id=crm-app-client"
        })
@Import({SecurityConfiguration.class, JwtRoleConverter.class, TaskAuthorization.class,
        RelationshipTaskApiExceptionHandler.class})
class RelationshipTaskActionControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RelationshipTaskService tasks;

    @Test
    void agentAcceptsTaskUsingVerifiedSubjectAndExpectedVersion() throws Exception {
        when(tasks.accept("task-1", 1, "agent-a", "correlation-1"))
                .thenReturn(task(RelationshipTask.Status.IN_REVIEW, 2));

        mockMvc.perform(post("/api/relationship-health/tasks/task-1/accept")
                        .header("X-Correlation-Id", "correlation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}")
                        .with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void missingCorrelationIdIsGenerated() throws Exception {
        when(tasks.accept(org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("agent-a"), anyString()))
                .thenReturn(task(RelationshipTask.Status.IN_REVIEW, 2));

        mockMvc.perform(post("/api/relationship-health/tasks/task-1/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}")
                        .with(agent()))
                .andExpect(status().isOk());

        ArgumentCaptor<String> correlation = ArgumentCaptor.forClass(String.class);
        verify(tasks).accept(org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("agent-a"),
                correlation.capture());
        assertThat(UUID.fromString(correlation.getValue())).isNotNull();
    }

    @Test
    void completesAndDismissesWithDispositionData() throws Exception {
        when(tasks.complete("task-1", 2, "agent-a", "correlation-1",
                "CLIENT_REVIEW_COMPLETED", "Reviewed"))
                .thenReturn(task(RelationshipTask.Status.COMPLETED, 3));
        when(tasks.dismiss("task-2", 1, "agent-a", "correlation-2",
                "EXPECTED_CLIENT_ACTIVITY", null))
                .thenReturn(task(RelationshipTask.Status.DISMISSED, 2));

        mockMvc.perform(post("/api/relationship-health/tasks/task-1/complete")
                        .header("X-Correlation-Id", "correlation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"outcomeCode":"CLIENT_REVIEW_COMPLETED","note":"Reviewed"}
                                """)
                        .with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(post("/api/relationship-health/tasks/task-2/dismiss")
                        .header("X-Correlation-Id", "correlation-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"reasonCode\":\"EXPECTED_CLIENT_ACTIVITY\"}")
                        .with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));
    }

    @Test
    void adminAndDualRoleTokensCannotMutate() throws Exception {
        String body = "{\"expectedVersion\":1}";
        mockMvc.perform(post("/api/relationship-health/tasks/task-1/accept")
                        .contentType(MediaType.APPLICATION_JSON).content(body).with(admin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/relationship-health/tasks/task-1/accept")
                        .contentType(MediaType.APPLICATION_JSON).content(body).with(jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_AGENT"),
                                new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(tasks);
    }

    @Test
    void rejectsMalformedActionRequests() throws Exception {
        mockMvc.perform(post("/api/relationship-health/tasks/task-1/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}")
                        .with(agent()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/relationship-health/tasks/task-1/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"outcomeCode\":\"\"}")
                        .with(agent()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedCompletionAndDismissalNotes() throws Exception {
        String oversized = "x".repeat(501);
        mockMvc.perform(post("/api/relationship-health/tasks/task-1/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"outcomeCode\":\"DONE\",\"note\":\""
                                + oversized + "\"}").with(agent()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/relationship-health/tasks/task-1/dismiss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"reasonCode\":\"EXPECTED\",\"note\":\""
                                + oversized + "\"}").with(agent()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(tasks);
    }

    @Test
    void mapsConcurrencyAndOwnershipFailuresWithoutLeakingTasks() throws Exception {
        when(tasks.accept("task-1", 1, "agent-a", "correlation-1"))
                .thenThrow(new OptimisticLockException("stale version"));
        when(tasks.accept("task-hidden", 1, "agent-a", "correlation-1"))
                .thenThrow(new TaskAccessException("assigned elsewhere"));

        mockMvc.perform(post("/api/relationship-health/tasks/task-1/accept")
                        .header("X-Correlation-Id", "correlation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}").with(agent()))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/relationship-health/tasks/task-hidden/accept")
                        .header("X-Correlation-Id", "correlation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}").with(agent()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Relationship task not found"));
    }

    @Test
    void mapsDispositionPolicyFailureToUnprocessableEntity() throws Exception {
        when(tasks.complete("task-1", 2, "agent-a", "correlation-1", "UNKNOWN", null))
                .thenThrow(new InvalidDispositionException("unsupported outcome"));

        mockMvc.perform(post("/api/relationship-health/tasks/task-1/complete")
                        .header("X-Correlation-Id", "correlation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"outcomeCode\":\"UNKNOWN\"}")
                        .with(agent()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Invalid disposition"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor agent() {
        return jwt().jwt(token -> token.subject("agent-a"))
                .authorities(new SimpleGrantedAuthority("ROLE_AGENT"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-a"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RelationshipTask task(RelationshipTask.Status status, long version) {
        Instant now = Instant.parse("2026-10-22T08:00:00Z");
        return new RelationshipTask("task-1", "client-1",
                new AssignmentSnapshot("client-1", "agent-a", true, 1, now),
                status, Severity.HIGH, version, now, now, List.of(), List.of());
    }
}
