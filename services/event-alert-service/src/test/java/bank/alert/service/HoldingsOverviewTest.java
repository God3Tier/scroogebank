package bank.alert.service;

import bank.alert.crm.AccountContext;
import bank.alert.crm.CrmDirectory;
import bank.alert.crm.DirectoryUnavailableException;
import bank.alert.domain.Instrument;
import bank.alert.fixture.SyntheticFixtureLoader;
import bank.alert.repository.InstrumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HoldingsOverviewTest {

    private static final UUID ACCOUNT = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID SAVINGS = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final UUID CLIENT = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("a0000000-0000-4000-8000-000000000003");
    private static final UUID OTHER_CLIENT = UUID.fromString("c0000000-0000-4000-8000-000000000002");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final SyntheticFixtureLoader loader = new SyntheticFixtureLoader(
            new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

    private static Instrument company(String id, String ticker, String name, String... official) {
        Instrument instrument = mock(Instrument.class);
        when(instrument.getId()).thenReturn(id);
        when(instrument.getTicker()).thenReturn(ticker);
        when(instrument.getIssuerName()).thenReturn(name);
        when(instrument.getOfficialDomains()).thenReturn(List.of(official));
        return instrument;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CrmDirectory> none() {
        ObjectProvider<CrmDirectory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CrmDirectory> directory(Map<UUID, AccountContext> accounts) {
        CrmDirectory crm = mock(CrmDirectory.class);
        when(crm.accounts(anyCollection())).thenReturn(accounts);
        ObjectProvider<CrmDirectory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(crm);
        return provider;
    }

    private static ResultSet row(UUID account, UUID client, String instrument, String quantity, String value, String total) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("account_id")).thenReturn(account.toString());
        when(row.getString("client_id")).thenReturn(client.toString());
        when(row.getString("instrument_id")).thenReturn(instrument);
        when(row.getBigDecimal("quantity")).thenReturn(new BigDecimal(quantity));
        when(row.getBigDecimal("value_sgd")).thenReturn(new BigDecimal(value));
        when(row.getBigDecimal("account_total")).thenReturn(new BigDecimal(total));
        return row;
    }

    /** The recorded positions the database holds, each as one row of the join. */
    private void recorded(ResultSet... rows) {
        doAnswer(call -> {
            RowCallbackHandler handler = call.getArgument(1);
            for (ResultSet row : rows) handler.processRow(row);
            return null;
        }).when(jdbc).query(contains("FROM current_position"), any(RowCallbackHandler.class));
    }

    private HoldingsOverview database(Map<UUID, AccountContext> accounts) {
        List<Instrument> companies = List.of(
                company("SGX-D05", "D05", "DBS Group Holdings Ltd", "sgx.com", "dbs.com"),
                company("SGX-O39", "O39", "Oversea-Chinese Banking Corporation Ltd", "ocbc.com"));
        when(instruments.findByActiveTrueOrderByIdAsc()).thenReturn(companies);
        return new HoldingsOverview(jdbc, loader, directory(accounts), instruments, "database");
    }

    @Test
    void databaseModeShowsTheClientsThatHoldEachCompanyWithTheirShareQuantityAndValue() throws SQLException {
        recorded(row(ACCOUNT, CLIENT, "SGX-D05", "1000", "36000", "47200"), row(ACCOUNT, CLIENT, "SGX-O39", "800", "11200", "47200"));
        HoldingsOverview overview = database(Map.of(
                ACCOUNT, new AccountContext(ACCOUNT, CLIENT, "INVESTMENT", "ACTIVE", "SGD", "agent-a")));

        List<HoldingsOverview.CompanyHoldings> companies = overview.forCaller("agent-a", false);

        assertThat(companies).extracting(HoldingsOverview.CompanyHoldings::ticker).containsExactly("D05", "O39");
        HoldingsOverview.CompanyHoldings dbs = companies.getFirst();
        assertThat(dbs.officialDomains()).containsExactly("sgx.com", "dbs.com");
        assertThat(dbs.holders()).singleElement().satisfies(holder -> {
            assertThat(holder.clientId()).isEqualTo(CLIENT.toString());
            assertThat(holder.quantity()).isEqualByComparingTo("1000");
            assertThat(holder.valueSgd()).isEqualByComparingTo("36000");
            assertThat(holder.sharePercent()).isEqualByComparingTo("76.3");
            assertThat(holder.ownerAgentSubject()).isNull();
        });
    }

    @Test
    void anAgentSeesOnlyTheirOwnClientsAndAnAdminSeesEveryoneWithTheOwningAgent() throws SQLException {
        recorded(row(ACCOUNT, CLIENT, "SGX-D05", "10", "100", "100"), row(OTHER_ACCOUNT, OTHER_CLIENT, "SGX-D05", "20", "200", "200"));
        HoldingsOverview overview = database(Map.of(
                ACCOUNT, new AccountContext(ACCOUNT, CLIENT, "INVESTMENT", "ACTIVE", "SGD", "agent-a"),
                OTHER_ACCOUNT, new AccountContext(OTHER_ACCOUNT, OTHER_CLIENT, "INVESTMENT", "ACTIVE", "SGD", "agent-b")));

        assertThat(overview.forCaller("agent-a", false).getFirst().holders())
                .extracting(HoldingsOverview.Holder::clientId).containsExactly(CLIENT.toString());
        List<HoldingsOverview.Holder> all = overview.forCaller("admin-1", true).getFirst().holders();
        assertThat(all).extracting(HoldingsOverview.Holder::ownerAgentSubject).containsExactlyInAnyOrder("agent-a", "agent-b");
        assertThat(overview.instrumentIdsFor("agent-b")).containsExactly("SGX-D05");
        assertThat(overview.allHeldInstrumentIds()).containsExactly("SGX-D05");
    }

    @Test
    void aClientWithHoldingsCountsBeforeAnyAlertExistsAndFollowsTheCrmOwnerAfterAReassignment() throws SQLException {
        recorded(row(ACCOUNT, CLIENT, "SGX-D05", "10", "100", "100"));
        HoldingsOverview overview = database(Map.of(
                ACCOUNT, new AccountContext(ACCOUNT, CLIENT, "INVESTMENT", "ACTIVE", "SGD", "agent-b")));

        assertThat(overview.instrumentIdsFor("agent-a")).isEmpty();
        assertThat(overview.instrumentIdsFor("agent-b")).containsExactly("SGX-D05");
    }

    @Test
    void accountsThatAreNotActiveInvestmentAccountsDoNotCount() throws SQLException {
        recorded(row(ACCOUNT, CLIENT, "SGX-D05", "10", "100", "100"), row(SAVINGS, CLIENT, "SGX-O39", "10", "100", "100"));
        HoldingsOverview overview = database(Map.of(
                ACCOUNT, new AccountContext(ACCOUNT, CLIENT, "INVESTMENT", "CLOSED", "SGD", "agent-a"),
                SAVINGS, new AccountContext(SAVINGS, CLIENT, "SAVINGS", "ACTIVE", "SGD", "agent-a")));

        assertThat(overview.forCaller("agent-a", false)).isEmpty();
    }

    @Test
    void databaseModeFailsRatherThanGuessWhenTheDirectoryIsMissing() {
        HoldingsOverview overview = new HoldingsOverview(jdbc, loader, none(), instruments, "database");

        assertThatThrownBy(() -> overview.forCaller("agent-a", false)).isInstanceOf(DirectoryUnavailableException.class);
    }

    @Test
    void fixtureModeReadsTheBundledHoldingsFile() {
        List<Instrument> companies = List.of(
                company("SGX-D05", "D05", "DBS Group Holdings Ltd", "dbs.com"),
                company("NYSE-DIS", "DIS", "Walt Disney Company", "thewaltdisneycompany.com"));
        when(instruments.findByActiveTrueOrderByIdAsc()).thenReturn(companies);
        HoldingsOverview overview = new HoldingsOverview(jdbc, loader, none(), instruments, "fixture");

        assertThat(overview.instrumentIdsFor("syn-agent-001")).contains("SGX-D05").doesNotContain("NYSE-DIS");
        assertThat(overview.instrumentIdsFor("syn-agent-003")).contains("NYSE-DIS");
        assertThat(overview.instrumentIdsFor("nobody")).isEmpty();
        assertThat(overview.forCaller("syn-agent-001", false).getFirst().holders()).allSatisfy(holder ->
                assertThat(holder.sharePercent()).isBetween(BigDecimal.ZERO, new BigDecimal("100.1")));
    }

    @Test
    void fixtureModeLetsAReassignmentTakePrecedenceOverTheFilesAgent() {
        List<Instrument> companies = List.of(company("SGX-D05", "D05", "DBS", "dbs.com"));
        when(instruments.findByActiveTrueOrderByIdAsc()).thenReturn(companies);
        doAnswer(call -> {
            RowCallbackHandler handler = call.getArgument(1);
            ResultSet moved = mock(ResultSet.class);
            for (String client : loader.loadDefault().positions().stream()
                    .filter(p -> "syn-agent-001".equals(p.assignedAgentSubject())).map(p -> p.clientRef()).distinct().toList()) {
                when(moved.getString(1)).thenReturn(client);
                when(moved.getString(2)).thenReturn("syn-agent-009");
                handler.processRow(moved);
            }
            return null;
        }).when(jdbc).query(contains("client_assignment"), any(RowCallbackHandler.class));
        HoldingsOverview overview = new HoldingsOverview(jdbc, loader, none(), instruments, "fixture");

        assertThat(overview.instrumentIdsFor("syn-agent-001")).isEmpty();
        assertThat(overview.instrumentIdsFor("syn-agent-009")).contains("SGX-D05");
    }
}
