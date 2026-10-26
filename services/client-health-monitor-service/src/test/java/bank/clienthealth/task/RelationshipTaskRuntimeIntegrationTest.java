package bank.clienthealth.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.RelationshipTaskPort;
import bank.clienthealth.contract.ClientHealthFinding.AnalysisWindow;
import bank.clienthealth.contract.ClientHealthFinding.PriorityInputs;
import bank.clienthealth.contract.ClientHealthFinding.RecurringCreditInterruptionEvidence;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalEvidence;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RelationshipTaskRuntimeIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");
    private static final AtomicReference<String> CURRENT_AGENT = new AtomicReference<>("agent-old");
    private static HttpServer assignmentServer;

    @DynamicPropertySource
    static void runtimeProperties(DynamicPropertyRegistry properties) {
        startAssignmentServer();
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "https://cognito.test/pool");
        properties.add("idp.cognito.app-client-id", () -> "crm-app-client");
        properties.add("client-health.assignment.base-url",
                RelationshipTaskRuntimeIntegrationTest::serverUrl);
        properties.add("client-health.assignment.oauth.token-uri",
                () -> serverUrl() + "/oauth2/token");
        properties.add("client-health.assignment.oauth.client-id", () -> "health-service");
        properties.add("client-health.assignment.oauth.client-secret", () -> "synthetic-secret");
        properties.add("client-health.assignment.oauth.scope", () -> "clients.assignment.read");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RelationshipTaskPort tasks;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearDatabase() {
        CURRENT_AGENT.set("agent-old");
        jdbc.execute("""
                TRUNCATE relationship_task_explanations, relationship_health_audit_outbox,
                    processed_client_health_findings, relationship_task_history,
                    relationship_task_evidence, task_assignment_snapshots, relationship_tasks,
                    agent_daily_task_admissions RESTART IDENTITY CASCADE
                """);
    }

    @AfterAll
    static void stopAssignmentServer() {
        if (assignmentServer != null) assignmentServer.stop(0);
    }

    @Test
    void migrationsRepositoriesSecurityControllersAndCurrentAssignmentWorkTogether() throws Exception {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('relationship_tasks', 'relationship_health_audit_outbox')
                """, Integer.class)).isEqualTo(2);
        String taskId = tasks.upsertFinding(finding()).taskId();
        assertThat(tasks.upsertFinding(finding()).taskId()).isEqualTo(taskId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM relationship_task_explanations", Integer.class)).isEqualTo(1);

        mockMvc.perform(get("/api/relationship-health/tasks").with(agent("agent-old")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].clientId").value("client-1"));

        CURRENT_AGENT.set("agent-new");
        mockMvc.perform(get("/api/relationship-health/tasks/{taskId}", taskId).with(agent("agent-old")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/relationship-health/tasks/{taskId}", taskId).with(agent("agent-new")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAgentSubject").value("agent-new"))
                .andExpect(jsonPath("$.explanation.source").value("TEMPLATE_FALLBACK"))
                .andExpect(jsonPath("$.explanation.text").value(
                        org.hamcrest.Matchers.containsString("Human review required")));
        mockMvc.perform(post("/api/relationship-health/tasks/{taskId}/accept", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}")
                        .with(agent("agent-new")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM relationship_tasks WHERE task_id = ?", String.class, taskId))
                .isEqualTo("IN_REVIEW");
        assertThat(jdbc.queryForList(
                "SELECT action FROM relationship_health_audit_outbox", String.class))
                .containsExactlyInAnyOrder("SIGNAL_DETECTED", "TASK_CREATED",
                        "EXPLANATION_FALLBACK_USED", "TASK_ACCEPTED");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor agent(String subject) {
        return jwt().jwt(token -> token.subject(subject))
                .authorities(new SimpleGrantedAuthority("ROLE_AGENT"));
    }

    private static ClientHealthFinding finding() {
        Instant now = Instant.parse("2026-10-23T00:00:00Z");
        Instant baselineStart = now.minusSeconds(120L * 86_400);
        Instant comparisonStart = now.minusSeconds(30L * 86_400);
        SignalWindow window = new SignalWindow(
                baselineStart, comparisonStart, comparisonStart, now.minusSeconds(60));
        var signal = new SignalEvidence<>("signal-runtime", "account-runtime",
                SignalType.RECURRING_CREDIT_INTERRUPTION, "rule-v1", Severity.HIGH, window,
                new RecurringCreditInterruptionEvidence(30, 3, 60), "a".repeat(64));
        return new ClientHealthFinding("1", "finding-runtime", "client-1", "run-runtime", "rules-v1",
                new AnalysisWindow(baselineStart, now), List.of(signal),
                new PriorityInputs(Severity.HIGH, 1), "correlation-runtime", now);
    }

    private static synchronized void startAssignmentServer() {
        if (assignmentServer != null) return;
        try {
            assignmentServer = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            assignmentServer.createContext("/oauth2/token", exchange -> respond(exchange, """
                    {"access_token":"synthetic-token","expires_in":3600}
                    """));
            assignmentServer.createContext("/internal/v1/clients/client-1/assignment", exchange ->
                    respond(exchange, """
                            {"schemaVersion":"1","clientId":"client-1",
                             "assignedAgentSubject":"%s","active":true,"assignmentVersion":%d,
                             "updatedAt":"2026-10-23T00:01:00Z"}
                            """.formatted(CURRENT_AGENT.get(),
                                    "agent-old".equals(CURRENT_AGENT.get()) ? 2 : 3)));
            assignmentServer.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start assignment HTTP stub", exception);
        }
    }

    private static String serverUrl() {
        return "http://" + assignmentServer.getAddress().getHostString()
                + ":" + assignmentServer.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
