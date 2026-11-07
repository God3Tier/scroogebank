package bank.engagement.testsupport;

import bank.engagement.integration.clients.ClientDirectory;
import bank.engagement.integration.identity.AgentDirectory;
import bank.engagement.integration.identity.AgentProfile;
import bank.engagement.integration.sources.FollowUpSources;
import bank.engagement.integration.sources.NewsReports;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;

/**
 * Boots the service against one PostgreSQL container shared by every
 * integration test, with Flyway applying the real migrations.
 *
 * <p>Properties are inlined rather than read from a test
 * {@code application.yml}, which would shadow the real one (the
 * dashboard-service convention). No Cognito pool is dialled: tests
 * authenticate with {@code SecurityMockMvcRequestPostProcessors.jwt()}.
 * Scheduling is off, so tests run the dispatcher themselves. client-service,
 * Cognito, Client Health and event-alert-service are mocked; mail goes to a
 * {@link RecordingMailChannel}.
 */
@SpringBootTest(properties = {
        "COGNITO_ISSUER_URI=https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_test",
        "COGNITO_APP_CLIENT_ID=test-app-client",
        "engagement.scheduling.enabled=false",
        "engagement.mail.min-send-interval=0s",
        "engagement.mail.from-address=no-reply@mail.example.test",
        "engagement.mail.mail-domain=mail.example.test"
})
@AutoConfigureMockMvc
@Import(TestBeans.class)
public abstract class PostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("engagement");

    static {
        POSTGRES.start();
    }

    public static final AgentProfile AGENT_PROFILE =
            new AgentProfile(Tokens.AGENT, "synthetic.agent@example.test", "Synthetic Agent", true);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean
    protected ClientDirectory clients;

    @MockitoBean
    protected AgentDirectory agents;

    @MockitoBean
    protected FollowUpSources sources;

    @MockitoBean
    protected NewsReports newsReports;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected RecordingMailChannel channel;

    @BeforeEach
    void resetState() {
        jdbc.execute("TRUNCATE events, mail_queue, email_draft_reports, email_draft_news, email_drafts, preferences, ai_usage, audit_outbox");
        clock.set(TestBeans.NOW);
        channel.reset();
        when(agents.find(Tokens.AGENT)).thenReturn(Optional.of(AGENT_PROFILE));
    }

    /** Audit payloads of one type, oldest first, as compact JSON. */
    protected List<String> audits(String outboxType) {
        return jdbc.queryForList("SELECT payload::text FROM audit_outbox WHERE event_type = ? ORDER BY created_at",
                        String.class, outboxType).stream()
                .map(payload -> payload.replace("\": ", "\":").replace(", \"", ",\""))
                .toList();
    }
}
