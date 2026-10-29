package bank.clienthealth.task.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.security.JwtRoleConverter;
import bank.clienthealth.security.SecurityConfiguration;
import bank.clienthealth.security.TaskAuthorization;
import bank.clienthealth.task.api.RelationshipTaskView.History;
import bank.clienthealth.task.api.RelationshipTaskView.Page;
import bank.clienthealth.task.api.RelationshipTaskView.Task;
import bank.clienthealth.task.application.RelationshipTaskQueryService;
import bank.clienthealth.task.application.RelationshipTaskService.TaskNotFoundException;
import bank.clienthealth.task.domain.RelationshipTask.Status;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.AdminViewer;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.AgentViewer;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.TaskFilter;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = RelationshipTaskQueryController.class,
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.test/pool",
            "idp.cognito.app-client-id=crm-app-client"
        })
@Import({SecurityConfiguration.class, JwtRoleConverter.class, TaskAuthorization.class,
        RelationshipTaskApiExceptionHandler.class})
class RelationshipTaskQueryControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RelationshipTaskQueryService queries;

    @Test
    void agentListUsesVerifiedSubjectAndFilters() throws Exception {
        when(queries.list(any(), any())).thenReturn(new Page("1", List.of(task()), "next"));

        mockMvc.perform(get("/api/relationship-health/tasks")
                        .param("status", "OPEN")
                        .param("severity", "HIGH")
                        .param("limit", "10")
                        .with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].taskId").value("task-1"))
                .andExpect(jsonPath("$.nextCursor").value("next"));

        verify(queries).list(new AgentViewer("agent-a"),
                new TaskFilter(Set.of(Status.OPEN), Set.of(Severity.HIGH), 10, null));
    }

    @Test
    void adminCanApplyAssignmentFilter() throws Exception {
        when(queries.list(any(), any())).thenReturn(new Page("1", List.of(), null));

        mockMvc.perform(get("/api/relationship-health/tasks")
                        .param("assignedAgentSubject", "agent-b")
                        .with(admin()))
                .andExpect(status().isOk());

        verify(queries).list(new AdminViewer("agent-b"),
                new TaskFilter(Set.of(), Set.of(), 25, null));
    }

    @Test
    void agentCannotUseAdminAssignmentFilter() throws Exception {
        mockMvc.perform(get("/api/relationship-health/tasks")
                        .param("assignedAgentSubject", "agent-b")
                        .with(agent()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(queries);
    }

    @Test
    void detailAndHistoryUseOwnershipScopedViewer() throws Exception {
        when(queries.detail("task-1", new AgentViewer("agent-a"))).thenReturn(task());
        when(queries.history("task-1", new AgentViewer("agent-a")))
                .thenReturn(new History("1", "task-1", List.of()));

        mockMvc.perform(get("/api/relationship-health/tasks/task-1").with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"));
        mockMvc.perform(get("/api/relationship-health/tasks/task-1/history").with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"));
    }

    @Test
    void hiddenTaskReturnsNotFoundWithoutLeakingOwnership() throws Exception {
        when(queries.detail("task-hidden", new AgentViewer("agent-a")))
                .thenThrow(new TaskNotFoundException("task-hidden"));

        mockMvc.perform(get("/api/relationship-health/tasks/task-hidden").with(agent()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Relationship task not found"));
    }

    @Test
    void rejectsUnauthenticatedAndUnsupportedRoles() throws Exception {
        mockMvc.perform(get("/api/relationship-health/tasks")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/relationship-health/tasks").with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_AUDITOR"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidFilters() throws Exception {
        mockMvc.perform(get("/api/relationship-health/tasks")
                        .param("status", "UNKNOWN")
                        .with(agent()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/relationship-health/tasks")
                        .param("limit", "101")
                        .with(agent()))
                .andExpect(status().isBadRequest());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor agent() {
        return jwt().jwt(token -> token.subject("agent-a"))
                .authorities(new SimpleGrantedAuthority("ROLE_AGENT"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-a"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static Task task() {
        Instant now = Instant.parse("2026-10-22T08:00:00Z");
        return new Task("1", "task-1", "client-1", "agent-a", Status.OPEN,
                Severity.HIGH, null, List.of(), 1, now, now);
    }
}
