package bank.alert.holdings;

import bank.alert.crm.AccountContext;
import bank.alert.crm.CrmDirectory;
import bank.alert.domain.HoldingSnapshot;
import bank.alert.domain.ValuationFreshness;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the maintained current portfolios and keeps only those the CRM still confirms:
 * the account exists, is an active investment account of the recorded client, and has a
 * current owner (the client's agent). Whole accounts are always returned, so a single
 * affected holding is never mistaken for the whole portfolio. A CRM that cannot be
 * asked throws; it never falls back to fixture data or an old assignment.
 */
@Component
@ConditionalOnProperty(name = "alert.holdings.mode", havingValue = "database")
public class CurrentHoldingsReader {

    static final String SOURCE_VERSION_PREFIX = "current-r";

    private final JdbcTemplate jdbc;
    private final CrmDirectory directory;
    private final ValuationFreshnessPolicy freshnessPolicy;

    public CurrentHoldingsReader(JdbcTemplate jdbc, CrmDirectory directory,
            ValuationFreshnessPolicy freshnessPolicy) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.freshnessPolicy = freshnessPolicy;
    }

    public record AccountPortfolio(UUID accountId, UUID clientId, String agentSubject, long revision,
            List<HoldingSnapshot> positions) {
    }

    /**
     * @param holdingAnyOf only accounts holding at least one of these, or null for all
     * @param agentSubject only this agent's clients, or null for every agent
     */
    public List<AccountPortfolio> read(Instant asOf, Set<String> holdingAnyOf, String agentSubject) {
        List<Row> rows = loadRows(holdingAnyOf);
        if (rows.isEmpty()) return List.of();

        Map<UUID, AccountContext> accounts = directory.accounts(
                rows.stream().map(Row::accountId).distinct().toList());
        Map<UUID, List<Row>> byAccount = new LinkedHashMap<>();
        for (Row row : rows) {
            AccountContext account = accounts.get(row.accountId());
            if (account != null && account.activeInvestment() && account.clientId().equals(row.clientId())) {
                byAccount.computeIfAbsent(row.accountId(), id -> new ArrayList<>()).add(row);
            }
        }
        if (byAccount.isEmpty()) return List.of();

        List<AccountPortfolio> result = new ArrayList<>();
        for (List<Row> accountRows : byAccount.values()) {
            String owner = accounts.get(accountRows.getFirst().accountId()).ownerAgentSubject();
            if (owner == null || owner.isBlank()) continue;
            if (agentSubject != null && !agentSubject.equals(owner)) continue;
            result.add(toPortfolio(accountRows, owner, asOf));
        }
        return result;
    }

    private AccountPortfolio toPortfolio(List<Row> accountRows, String agent, Instant asOf) {
        Row first = accountRows.getFirst();
        List<ValuationFreshness> freshness = accountRows.stream()
                .map(row -> classify(row.valuedAt(), asOf)).toList();
        // Same content and freshness -> same snapshot group, so repeated runs reuse its rows.
        StringBuilder signature = new StringBuilder("psnap|").append(first.accountId())
                .append('|').append(first.revision());
        for (int i = 0; i < accountRows.size(); i++) {
            signature.append('|').append(accountRows.get(i).instrumentId()).append(':').append(freshness.get(i));
        }
        UUID group = UUID.nameUUIDFromBytes(signature.toString().getBytes(StandardCharsets.UTF_8));
        List<HoldingSnapshot> positions = new ArrayList<>();
        for (int i = 0; i < accountRows.size(); i++) {
            Row row = accountRows.get(i);
            positions.add(new HoldingSnapshot(
                    UUID.nameUUIDFromBytes((group + "|" + row.instrumentId()).getBytes(StandardCharsets.UTF_8)),
                    first.clientId().toString(), first.accountId().toString(), agent, row.instrumentId(),
                    row.quantity(), row.unitPrice(), row.currency(), row.fxToSgd(), row.valueSgd(),
                    row.valuedAt(), freshness.get(i), SOURCE_VERSION_PREFIX + first.revision(),
                    group, first.revision()));
        }
        return new AccountPortfolio(first.accountId(), first.clientId(), agent, first.revision(), positions);
    }

    private ValuationFreshness classify(Instant valuedAt, Instant asOf) {
        return valuedAt.isAfter(asOf) ? ValuationFreshness.MISSING : freshnessPolicy.classify(valuedAt, asOf);
    }

    private List<Row> loadRows(Set<String> holdingAnyOf) {
        String sql = """
                SELECT cp.account_id, cp.client_id, cp.revision, p.instrument_id, p.quantity,
                       p.unit_price, p.valuation_currency, p.fx_to_sgd, p.value_sgd, p.valued_at
                  FROM current_portfolio cp
                  JOIN current_position p ON p.account_id = cp.account_id
                 WHERE cp.revision > 0
                """;
        List<Object> args = new ArrayList<>();
        if (holdingAnyOf != null) {
            if (holdingAnyOf.isEmpty()) return List.of();
            sql += " AND cp.account_id IN (SELECT account_id FROM current_position WHERE instrument_id IN ("
                    + String.join(",", Collections.nCopies(holdingAnyOf.size(), "?")) + "))";
            args.addAll(holdingAnyOf);
        }
        sql += " ORDER BY cp.account_id, p.instrument_id";
        return jdbc.query(sql, (rs, n) -> new Row(rs.getObject("account_id", UUID.class),
                rs.getObject("client_id", UUID.class), rs.getLong("revision"),
                rs.getString("instrument_id"), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"), rs.getString("valuation_currency"),
                rs.getBigDecimal("fx_to_sgd"), rs.getBigDecimal("value_sgd"),
                ((Timestamp) rs.getTimestamp("valued_at")).toInstant()), args.toArray());
    }

    private record Row(UUID accountId, UUID clientId, long revision, String instrumentId,
            BigDecimal quantity, BigDecimal unitPrice, String currency, BigDecimal fxToSgd,
            BigDecimal valueSgd, Instant valuedAt) {
    }
}
