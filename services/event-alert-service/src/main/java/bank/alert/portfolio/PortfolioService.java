package bank.alert.portfolio;

import bank.alert.crm.AccountContext;
import bank.alert.crm.ClientContext;
import bank.alert.crm.CrmDirectory;
import bank.alert.domain.AuditOutbox;
import bank.alert.domain.ValuationFreshness;
import bank.alert.holdings.ValuationFreshnessPolicy;
import bank.alert.repository.AuditOutboxRepository;
import bank.alert.service.AuditUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


/**
 * Records the dummy securities positions an agent holds against an existing CRM
 * account (ADR-0066). The CRM remains the authority on whether the account exists,
 * is an active investment account, and who currently owns the client.
 */
@Service
@ConditionalOnProperty(name = "alert.holdings.mode", havingValue = "database")
public class PortfolioService {

    static final int MAX_POSITIONS = 100;
    private static final BigDecimal MAX_VALUE = new BigDecimal("999999999999999.9999");
    private static final int DECIMAL_SCALE = 8;
    private static final List<String> BACKLOG_STATUSES = List.of("PENDING", "FAILED");

    private final JdbcTemplate jdbc;
    private final CrmDirectory directory;
    private final AuditOutboxRepository outbox;
    private final ValuationFreshnessPolicy freshness;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int maxPendingAuditEvents;

    public PortfolioService(JdbcTemplate jdbc, CrmDirectory directory, AuditOutboxRepository outbox,
            ValuationFreshnessPolicy freshness, ObjectMapper mapper, Clock clock,
            @Value("${alert.audit.max-pending:10000}") int maxPendingAuditEvents) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.outbox = outbox;
        this.freshness = freshness;
        this.mapper = mapper;
        this.clock = clock;
        this.maxPendingAuditEvents = maxPendingAuditEvents;
    }

    @Transactional(readOnly = true)
    public PortfolioResponse get(UUID accountId, String actorSubject, boolean admin) {
        AccountContext account = visibleAccount(accountId, actorSubject, admin);
        return load(account.accountId()).orElseThrow(PortfolioApiException::notFound);
    }

    @Transactional
    public PortfolioResponse replace(UUID accountId, String actorSubject, String idempotencyKey,
            UUID correlationId, PortfolioReplaceRequest request) {
        AccountContext account = visibleAccount(accountId, actorSubject, false);
        if (!account.activeInvestment()) {
            throw PortfolioApiException.conflict("holdings can only be recorded on an active investment account");
        }
        Instant now = clock.instant();
        List<ValidPosition> positions = validate(request, now);
        String hash = requestHash(accountId, request);

        List<Map<String, Object>> replay = jdbc.queryForList("""
                SELECT account_id, request_hash, resulting_revision FROM portfolio_write_request
                 WHERE actor_subject = ? AND idempotency_key = ?
                """, actorSubject, idempotencyKey);
        if (!replay.isEmpty()) {
            Map<String, Object> prior = replay.getFirst();
            PortfolioResponse current = load(accountId).orElse(null);
            if (!accountId.equals(prior.get("account_id")) || !hash.equals(prior.get("request_hash"))
                    || current == null
                    || current.revision() != ((Number) prior.get("resulting_revision")).longValue()) {
                throw PortfolioApiException.conflict("idempotency key was used for another replacement");
            }
            return current;
        }

        ensureAuditCapacity();
        // Create the row if absent so concurrent first writers serialise on the lock below.
        jdbc.update("""
                INSERT INTO current_portfolio (account_id, client_id, revision, source_label, updated_at, updated_by)
                VALUES (?, ?, 0, ?, ?, ?) ON CONFLICT (account_id) DO NOTHING
                """, accountId, account.clientId(), request.sourceLabel(), Timestamp.from(now), actorSubject);
        Map<String, Object> locked = jdbc.queryForMap(
                "SELECT revision, client_id FROM current_portfolio WHERE account_id = ? FOR UPDATE", accountId);
        long currentRevision = ((Number) locked.get("revision")).longValue();
        if (currentRevision != request.expectedRevision()) {
            throw PortfolioApiException.conflict("expected portfolio revision is stale");
        }
        long newRevision = currentRevision + 1;
        jdbc.update("""
                UPDATE current_portfolio SET client_id = ?, revision = ?, source_label = ?,
                       updated_at = ?, updated_by = ? WHERE account_id = ?
                """, account.clientId(), newRevision, request.sourceLabel(), Timestamp.from(now),
                actorSubject, accountId);
        jdbc.update("DELETE FROM current_position WHERE account_id = ?", accountId);
        for (ValidPosition p : positions) {
            jdbc.update("""
                    INSERT INTO current_position (account_id, instrument_id, quantity, unit_price,
                        valuation_currency, fx_to_sgd, value_sgd, valued_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, accountId, p.instrumentId(), p.quantity(), p.unitPrice(), p.currency(),
                    p.fxToSgd(), p.valueSgd(), Timestamp.from(p.valuedAt()));
        }
        jdbc.update("""
                INSERT INTO portfolio_write_request (actor_subject, idempotency_key, account_id,
                    request_hash, resulting_revision, created_at) VALUES (?, ?, ?, ?, ?, ?)
                """, actorSubject, idempotencyKey, accountId, hash, newRevision, Timestamp.from(now));
        persistAudit(accountId, account.clientId(), actorSubject, correlationId, currentRevision,
                newRevision, positions.size(), now);
        return load(accountId).orElseThrow();
    }

    /** Loads a stored portfolio, or empty when none was ever recorded for the account. */
    Optional<PortfolioResponse> load(UUID accountId) {
        List<Map<String, Object>> header = jdbc.queryForList("""
                SELECT client_id, revision, source_label, updated_at FROM current_portfolio
                 WHERE account_id = ? AND revision > 0
                """, accountId);
        if (header.isEmpty()) return Optional.empty();
        Instant asOf = clock.instant();
        List<PortfolioResponse.PositionResponse> positions = jdbc.query("""
                SELECT p.instrument_id, i.ticker, i.issuer_name, p.quantity, p.unit_price,
                       p.valuation_currency, p.fx_to_sgd, p.value_sgd, p.valued_at
                  FROM current_position p JOIN instrument i ON i.id = p.instrument_id
                 WHERE p.account_id = ? ORDER BY p.instrument_id
                """, (rs, row) -> {
            Instant valuedAt = rs.getTimestamp("valued_at").toInstant();
            return new PortfolioResponse.PositionResponse(
                    rs.getString("instrument_id"), rs.getString("ticker"), rs.getString("issuer_name"),
                    rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"),
                    rs.getString("valuation_currency"), rs.getBigDecimal("fx_to_sgd"),
                    rs.getBigDecimal("value_sgd"), valuedAt, classify(valuedAt, asOf));
        }, accountId);
        BigDecimal total = positions.stream().map(PortfolioResponse.PositionResponse::valueSgd)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(4, RoundingMode.HALF_UP);
        Map<String, Object> h = header.getFirst();
        return Optional.of(new PortfolioResponse(accountId, (UUID) h.get("client_id"),
                ((Number) h.get("revision")).longValue(), (String) h.get("source_label"),
                ((Timestamp) h.get("updated_at")).toInstant(), total, positions));
    }

    private ValuationFreshness classify(Instant valuedAt, Instant asOf) {
        return valuedAt.isAfter(asOf) ? ValuationFreshness.MISSING : freshness.classify(valuedAt, asOf);
    }

    /**
     * Resolves the account from the CRM and checks it belongs to a client the caller
     * currently owns. Not-found and not-owned look identical, so IDs cannot be probed.
     */
    private AccountContext visibleAccount(UUID accountId, String actorSubject, boolean admin) {
        AccountContext account = directory.accounts(Set.of(accountId)).get(accountId);
        if (account == null) throw PortfolioApiException.notFound();
        if (admin) return account;
        ClientContext client = directory.clients(Set.of(account.clientId())).get(account.clientId());
        if (client == null || client.assignedAgentSubject() == null
                || !client.assignedAgentSubject().equals(actorSubject)) {
            throw PortfolioApiException.notFound();
        }
        return account;
    }

    private List<ValidPosition> validate(PortfolioReplaceRequest request, Instant now) {
        if (request.positions().size() > MAX_POSITIONS) {
            throw PortfolioApiException.unprocessable("a portfolio holds at most " + MAX_POSITIONS + " positions");
        }
        Set<String> seen = new HashSet<>();
        List<ValidPosition> valid = new java.util.ArrayList<>();
        for (PortfolioReplaceRequest.Position p : request.positions()) {
            if (!seen.add(p.instrumentId())) {
                throw PortfolioApiException.unprocessable("duplicate instrument in portfolio");
            }
            requirePositive(p.quantity(), "quantity");
            requirePositive(p.unitPrice(), "unitPrice");
            requirePositive(p.fxToSgd(), "fxToSgd");
            if (p.valuedAt().isAfter(now)) {
                throw PortfolioApiException.unprocessable("valuedAt cannot be in the future");
            }
            BigDecimal value = p.quantity().multiply(p.unitPrice()).multiply(p.fxToSgd())
                    .setScale(4, RoundingMode.HALF_UP);
            if (value.compareTo(MAX_VALUE) > 0 || value.signum() <= 0) {
                throw PortfolioApiException.unprocessable("position value is out of range");
            }
            valid.add(new ValidPosition(p.instrumentId(), p.quantity(), p.unitPrice(),
                    p.valuationCurrency(), p.fxToSgd(), value, p.valuedAt()));
        }
        if (!valid.isEmpty()) {
            List<String> known = jdbc.queryForList(
                    "SELECT id FROM instrument WHERE active = TRUE AND id IN ("
                            + String.join(",", java.util.Collections.nCopies(valid.size(), "?")) + ")",
                    String.class, valid.stream().map(ValidPosition::instrumentId).toArray());
            if (known.size() != valid.size()) {
                throw PortfolioApiException.unprocessable("unsupported instrument in portfolio");
            }
        }
        return valid;
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value.signum() <= 0 || value.scale() > DECIMAL_SCALE
                || value.precision() - value.scale() > 16) {
            throw PortfolioApiException.unprocessable(field + " must be positive with at most 8 decimal places");
        }
    }

    private void ensureAuditCapacity() {
        try {
            if (outbox.countByStatusIn(BACKLOG_STATUSES) >= maxPendingAuditEvents) {
                throw new AuditUnavailableException("audit outbox is at capacity");
            }
        } catch (DataAccessException exception) {
            throw new AuditUnavailableException("audit outbox availability cannot be verified", exception);
        }
    }

    private void persistAudit(UUID accountId, UUID clientId, String actor, UUID correlationId,
            long before, long after, int positionCount, Instant now) {
        // Opaque IDs and counts only: no quantities, valuations or client names.
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("clientId", clientId);
        payload.put("actorSubject", actor);
        payload.put("actorRole", "AGENT");
        payload.put("correlationId", correlationId);
        payload.put("revisionBefore", before);
        payload.put("revisionAfter", after);
        payload.put("positionCount", positionCount);
        payload.put("outcome", positionCount == 0 ? "CLEARED" : "REPLACED");
        payload.put("occurredAt", now.toString());
        try {
            outbox.saveAndFlush(new AuditOutbox(UUID.randomUUID(),
                    positionCount == 0 ? "alert.portfolio-cleared" : "alert.portfolio-replaced",
                    1, mapper.writeValueAsString(payload), now));
        } catch (DataAccessException | JsonProcessingException exception) {
            throw new AuditUnavailableException("durable audit persistence is unavailable", exception);
        }
    }

    private static String requestHash(UUID accountId, PortfolioReplaceRequest request) {
        StringBuilder canonical = new StringBuilder()
                .append(accountId).append('|').append(request.expectedRevision())
                .append('|').append(request.sourceLabel());
        request.positions().stream().sorted(Comparator.comparing(PortfolioReplaceRequest.Position::instrumentId))
                .forEach(p -> canonical.append('|').append(p.instrumentId()).append(',')
                        .append(p.quantity().stripTrailingZeros().toPlainString()).append(',')
                        .append(p.unitPrice().stripTrailingZeros().toPlainString()).append(',')
                        .append(p.valuationCurrency()).append(',')
                        .append(p.fxToSgd().stripTrailingZeros().toPlainString()).append(',')
                        .append(p.valuedAt()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record ValidPosition(String instrumentId, BigDecimal quantity, BigDecimal unitPrice,
            String currency, BigDecimal fxToSgd, BigDecimal valueSgd, Instant valuedAt) {
    }
}
