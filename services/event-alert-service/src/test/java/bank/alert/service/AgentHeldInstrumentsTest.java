package bank.alert.service;

import bank.alert.fixture.SyntheticFixtureLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class AgentHeldInstrumentsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SyntheticFixtureLoader loader = new SyntheticFixtureLoader(
            new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

    @Test
    void fixtureModeReadsTheBundledHoldingsFileForTheAgent() {
        AgentHeldInstruments held = new AgentHeldInstruments(jdbc, loader, "fixture");

        assertThat(held.idsFor("syn-agent-001")).contains("SGX-D05", "SGX-Z74").doesNotContain("NYSE-DIS");
        assertThat(held.holds("syn-agent-003", "NYSE-DIS")).isTrue();
        assertThat(held.idsFor("nobody")).isEmpty();
    }

    @Test
    void aReassignmentTakesPrecedenceOverTheFilesAgent() {
        // Every client of syn-agent-001 is reassigned to syn-agent-009.
        doAnswer(call -> {
            RowCallbackHandler handler = call.getArgument(1);
            java.sql.ResultSet row = mock(java.sql.ResultSet.class);
            for (String client : loader.loadDefault().positions().stream()
                    .filter(p -> "syn-agent-001".equals(p.assignedAgentSubject()))
                    .map(p -> p.clientRef()).distinct().toList()) {
                org.mockito.Mockito.when(row.getString(1)).thenReturn(client);
                org.mockito.Mockito.when(row.getString(2)).thenReturn("syn-agent-009");
                handler.processRow(row);
            }
            return null;
        }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.any(RowCallbackHandler.class));
        AgentHeldInstruments held = new AgentHeldInstruments(jdbc, loader, "fixture");

        assertThat(held.idsFor("syn-agent-001")).isEmpty();
        assertThat(held.idsFor("syn-agent-009")).contains("SGX-D05");
    }
}
