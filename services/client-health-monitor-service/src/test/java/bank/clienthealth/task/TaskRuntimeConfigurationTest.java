package bank.clienthealth.task;

import static org.assertj.core.api.Assertions.assertThat;

import bank.clienthealth.activity.application.ActivityBaselineService;
import bank.clienthealth.activity.application.ActivityProjectionService;
import bank.clienthealth.activity.persistence.JdbcActivityProjectionRepository;
import bank.clienthealth.analysis.persistence.JdbcAnalysisRunRepository;
import bank.clienthealth.audit.AuditOutboxRelay;
import bank.clienthealth.audit.JdbcAuditOutboxRepository;
import bank.clienthealth.runtime.AnalysisRunner;
import bank.clienthealth.task.api.RelationshipTaskActionController;
import bank.clienthealth.task.api.RelationshipTaskQueryController;
import bank.clienthealth.task.application.RelationshipTaskQueryService;
import bank.clienthealth.task.application.RelationshipTaskService;
import bank.clienthealth.task.persistence.JdbcRelationshipTaskRepository;
import bank.clienthealth.task.port.ClientAssignmentPort;
import bank.clienthealth.task.port.DispositionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    "management.endpoint.health.validate-group-membership=false",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.test/pool",
    "idp.cognito.app-client-id=crm-app-client"
})
@AutoConfigureMockMvc
class TaskRuntimeConfigurationTest {
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean PlatformTransactionManager transactionManager;

    @Autowired JdbcRelationshipTaskRepository repository;
    @Autowired ClientAssignmentPort assignments;
    @Autowired DispositionPolicy dispositionPolicy;
    @Autowired RelationshipTaskService tasks;
    @Autowired RelationshipTaskQueryService queries;
    @Autowired RelationshipTaskActionController actions;
    @Autowired RelationshipTaskQueryController reads;
    @Autowired JdbcActivityProjectionRepository activityRepository;
    @Autowired ActivityProjectionService activityProjection;
    @Autowired ActivityBaselineService activityBaselines;
    @Autowired JdbcAnalysisRunRepository analysisRuns;
    @Autowired JdbcAuditOutboxRepository auditOutbox;
    @Autowired ApplicationContext context;
    @Autowired MockMvc mockMvc;

    @Test
    void applicationStartsWithEveryProductionTaskApiDependency() {
        assertThat(repository).isNotNull();
        assertThat(assignments).isNotNull();
        assertThat(dispositionPolicy).isNotNull();
        assertThat(tasks).isNotNull();
        assertThat(queries).isNotNull();
        assertThat(actions).isNotNull();
        assertThat(reads).isNotNull();
        assertThat(activityRepository).isNotNull();
        assertThat(activityProjection).isNotNull();
        assertThat(activityBaselines).isNotNull();
        assertThat(analysisRuns).isNotNull();
        assertThat(auditOutbox).isNotNull();
        assertThat(context.getBeansOfType(AuditOutboxRelay.class)).isEmpty();
        assertThat(context.getBeansOfType(AnalysisRunner.class)).isEmpty();
    }

    @Test
    void healthProbesAreAnonymousButMetricsAndBusinessRoutesAreNot() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/relationship-health/tasks")).andExpect(status().isUnauthorized());
    }
}
