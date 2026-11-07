package bank.alert.service;

import bank.alert.crm.AccountContext;
import bank.alert.crm.CrmDirectory;
import bank.alert.crm.CurrentOwnership;
import bank.alert.crm.DirectoryUnavailableException;
import bank.alert.domain.Instrument;
import bank.alert.fixture.SyntheticFixtureLoader;
import bank.alert.fixture.SyntheticHoldingFixture;
import bank.alert.repository.InstrumentRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Which companies the clients of an agent (or of everyone, for an admin) hold right now, and which
 * clients hold each one (ADR-0082). Client names are not here: the page looks them up through the
 * CRM with the user's own login, so the alerts database keeps only anonymous ids.
 *
 * <p>In database mode the recorded positions are matched to their accounts and the CRM is asked who
 * owns each account now (active investment accounts only), so a client with holdings but no alert
 * yet counts and a reassignment moves the companies with the client (ADR-0066). In fixture mode it
 * is the bundled holdings file, the same source the alerts use, with a reassignment in
 * {@code client_assignment} taking precedence over the file's agent.
 */
@Component
public class HoldingsOverview {

    /** One client's holding of one company. The owner is given only when the caller is an admin. */
    public record Holder(String clientId, String accountId, BigDecimal quantity, BigDecimal valueSgd,
            BigDecimal sharePercent, String ownerAgentSubject) {}

    public record CompanyHoldings(String instrumentId, String ticker, String issuerName,
            List<String> officialDomains, List<Holder> holders) {}

    private record Position(String clientId, String accountId, String instrumentId, BigDecimal quantity,
            BigDecimal valueSgd, BigDecimal accountTotal, String owner) {}

    private final JdbcTemplate jdbc;
    private final SyntheticFixtureLoader fixtureLoader;
    private final ObjectProvider<CrmDirectory> directory;
    private final InstrumentRepository instruments;
    private final boolean databaseMode;

    public HoldingsOverview(JdbcTemplate jdbc, SyntheticFixtureLoader fixtureLoader,
            ObjectProvider<CrmDirectory> directory, InstrumentRepository instruments,
            @Value("${alert.holdings.mode:fixture}") String holdingsMode) {
        this.jdbc = jdbc;
        this.fixtureLoader = fixtureLoader;
        this.directory = directory;
        this.instruments = instruments;
        this.databaseMode = "database".equalsIgnoreCase(holdingsMode);
    }

    /** The companies held by this agent's clients, or by every client when {@code admin} is true. */
    public List<CompanyHoldings> forCaller(String agentSubject, boolean admin) {
        List<Position> positions = (databaseMode ? recordedPositions() : fixturePositions()).stream()
                .filter(position -> admin || agentSubject.equals(position.owner()))
                .toList();
        Map<String, Instrument> companies = new HashMap<>();
        instruments.findByActiveTrueOrderByIdAsc().forEach(instrument -> companies.put(instrument.getId(), instrument));
        Map<String, List<Holder>> byCompany = new LinkedHashMap<>();
        for (Position position : positions) {
            if (!companies.containsKey(position.instrumentId())) continue;
            BigDecimal share = position.accountTotal().signum() == 0 ? BigDecimal.ZERO
                    : position.valueSgd().multiply(BigDecimal.valueOf(100))
                            .divide(position.accountTotal(), 1, RoundingMode.HALF_UP);
            byCompany.computeIfAbsent(position.instrumentId(), id -> new ArrayList<>())
                    .add(new Holder(position.clientId(), position.accountId(), position.quantity(),
                            position.valueSgd(), share, admin ? position.owner() : null));
        }
        List<CompanyHoldings> result = new ArrayList<>();
        for (Map.Entry<String, List<Holder>> entry : byCompany.entrySet()) {
            Instrument company = companies.get(entry.getKey());
            entry.getValue().sort(Comparator.comparing(Holder::sharePercent).reversed());
            result.add(new CompanyHoldings(company.getId(), company.getTicker(), company.getIssuerName(),
                    company.getOfficialDomains(), entry.getValue()));
        }
        result.sort(Comparator.comparing(CompanyHoldings::ticker));
        return result;
    }

    /** Just the ids of the companies this agent's clients hold. */
    public List<String> instrumentIdsFor(String agentSubject) {
        return forCaller(agentSubject, false).stream().map(CompanyHoldings::instrumentId).toList();
    }

    /** Every company any client holds, for an admin's search. */
    public List<String> allHeldInstrumentIds() {
        return forCaller("", true).stream().map(CompanyHoldings::instrumentId).toList();
    }

    private List<Position> recordedPositions() {
        CrmDirectory crm = directory.getIfAvailable();
        if (crm == null) throw new DirectoryUnavailableException("the CRM directory is not configured");
        List<Position> rows = new ArrayList<>();
        jdbc.query("""
                SELECT cpos.account_id, port.client_id, cpos.instrument_id, cpos.quantity, cpos.value_sgd,
                       SUM(cpos.value_sgd) OVER (PARTITION BY cpos.account_id) AS account_total
                  FROM current_position cpos
                  JOIN current_portfolio port ON port.account_id = cpos.account_id
                """, rs -> {
            rows.add(new Position(rs.getString("client_id"), rs.getString("account_id"), rs.getString("instrument_id"),
                    rs.getBigDecimal("quantity"), rs.getBigDecimal("value_sgd"), rs.getBigDecimal("account_total"), null));
        });
        Set<UUID> accountIds = new TreeSet<>();
        rows.forEach(row -> accountIds.add(UUID.fromString(row.accountId())));
        if (accountIds.size() > CurrentOwnership.MAX_REFERENCED_CLIENTS) {
            throw new DirectoryUnavailableException("too many accounts to verify ownership");
        }
        Map<UUID, AccountContext> owners = new HashMap<>();
        List<UUID> ids = new ArrayList<>(accountIds);
        for (int from = 0; from < ids.size(); from += CrmDirectory.MAX_LOOKUP_IDS) {
            owners.putAll(crm.accounts(ids.subList(from, Math.min(ids.size(), from + CrmDirectory.MAX_LOOKUP_IDS))));
        }
        List<Position> owned = new ArrayList<>();
        for (Position row : rows) {
            AccountContext account = owners.get(UUID.fromString(row.accountId()));
            if (account != null && account.activeInvestment() && account.ownerAgentSubject() != null
                    && !account.ownerAgentSubject().isBlank()) {
                owned.add(new Position(row.clientId(), row.accountId(), row.instrumentId(), row.quantity(),
                        row.valueSgd(), row.accountTotal(), account.ownerAgentSubject()));
            }
        }
        return owned;
    }

    private List<Position> fixturePositions() {
        Map<String, String> reassigned = new HashMap<>();
        jdbc.query("SELECT client_ref, agent_subject FROM client_assignment",
                rs -> { reassigned.put(rs.getString(1), rs.getString(2)); });
        List<SyntheticHoldingFixture.Position> held = fixtureLoader.loadDefault().positions().stream()
                .filter(position -> position.quantity().signum() > 0)
                .toList();
        Map<String, BigDecimal> totals = new HashMap<>();
        held.forEach(position -> totals.merge(position.accountRef(), position.valueSgd(), BigDecimal::add));
        return held.stream()
                .map(position -> new Position(position.clientRef(), position.accountRef(), position.instrumentId(),
                        position.quantity(), position.valueSgd(), totals.get(position.accountRef()),
                        reassigned.getOrDefault(position.clientRef(), position.assignedAgentSubject())))
                .toList();
    }
}
