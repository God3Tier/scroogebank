package bank.alert.service;

import bank.alert.crm.AccountContext;
import bank.alert.crm.CrmDirectory;
import bank.alert.crm.DirectoryUnavailableException;
import bank.alert.fixture.SyntheticFixtureLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class AgentHeldInstrumentsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SyntheticFixtureLoader loader = new SyntheticFixtureLoader(
            new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

    @Test
    void fixtureModeReadsTheBundledHoldingsFileForTheAgent() {
        AgentHeldInstruments held = new AgentHeldInstruments(jdbc, loader, none(), "fixture");

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
        AgentHeldInstruments held = new AgentHeldInstruments(jdbc, loader, none(), "fixture");

        assertThat(held.idsFor("syn-agent-001")).isEmpty();
        assertThat(held.idsFor("syn-agent-009")).contains("SGX-D05");
    }

    private static final UUID ACCOUNT = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID SAVINGS = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final UUID CLIENT = UUID.fromString("c0000000-0000-4000-8000-000000000001");

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CrmDirectory> none() {
        ObjectProvider<CrmDirectory> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CrmDirectory> directory(Map<UUID, AccountContext> accounts) {
        CrmDirectory crm = mock(CrmDirectory.class);
        org.mockito.Mockito.when(crm.accounts(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(accounts);
        ObjectProvider<CrmDirectory> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(crm);
        return provider;
    }

    private void recordedPositions(UUID account, String... instruments) {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (String instrument : instruments) rows.add(new String[] {account.toString(), instrument});
        recordedRows(rows);
    }

    private void recordedRows(java.util.List<String[]> rows) {
        doAnswer(call -> {
            org.springframework.jdbc.core.RowCallbackHandler handler = call.getArgument(1);
            java.sql.ResultSet row = mock(java.sql.ResultSet.class);
            for (String[] recorded : rows) {
                org.mockito.Mockito.when(row.getString(1)).thenReturn(recorded[0]);
                org.mockito.Mockito.when(row.getString(2)).thenReturn(recorded[1]);
                handler.processRow(row);
            }
            return null;
        }).when(jdbc).query(org.mockito.ArgumentMatchers.eq("SELECT account_id, instrument_id FROM current_position"),
                org.mockito.ArgumentMatchers.any(RowCallbackHandler.class));
    }

    @Test
    void databaseModeCountsAClientWithHoldingsEvenBeforeAnyAlertExists() {
        recordedPositions(ACCOUNT, "SGX-D05", "SGX-Z74");
        AgentHeldInstruments held = new AgentHeldInstruments(jdbc, loader, directory(Map.of(
                ACCOUNT, new AccountContext(ACCOUNT, CLIENT, "INVESTMENT", "ACTIVE", "SGD", "agent-a"))), "database");

        assertThat(held.idsFor("agent-a")).containsExactly("SGX-D05", "SGX-Z74");
        assertThat(held.holds("agent-a", "SGX-D05")).isTrue();
        assertThat(held.idsFor("agent-b")).isEmpty();
    }

    @Test
    void databaseModeFollowsTheCrmOwnerAfterAReassignment() {
        recordedPositions(ACCOUNT, "SGX-D05");
        AgentHeldInstruments held = new AgentHeldInstruments(jdbc, loader, directory(Map.of(
                ACCOUNT, new AccountContext(ACCOUNT, CLIENT, "INVESTMENT", "ACTIVE", "SGD", "agent-b"))), "database");

        assertThat(held.idsFor("agent-a")).isEmpty();
        assertThat(held.idsFor("agent-b")).containsExactly("SGX-D05");
    }

    @Test
    void databaseModeIgnoresAccountsThatAreNotActiveInvestmentAccounts() {
        recordedRows(java.util.List.of(new String[] {ACCOUNT.toString(), "SGX-D05"}, new String[] {SAVINGS.toString(), "SGX-Z74"}));
        AgentHeldInstruments held = new AgentHeldInstruments(jdbc, loader, directory(Map.of(
                ACCOUNT, new AccountContext(ACCOUNT, CLIENT, "INVESTMENT", "CLOSED", "SGD", "agent-a"),
                SAVINGS, new AccountContext(SAVINGS, CLIENT, "SAVINGS", "ACTIVE", "SGD", "agent-a"))), "database");

        assertThat(held.idsFor("agent-a")).isEmpty();
    }

    @Test
    void databaseModeFailsRatherThanGuessWhenTheDirectoryIsMissing() {
        AgentHeldInstruments held = new AgentHeldInstruments(jdbc, loader, none(), "database");

        assertThatThrownBy(() -> held.idsFor("agent-a")).isInstanceOf(DirectoryUnavailableException.class);
    }
}
