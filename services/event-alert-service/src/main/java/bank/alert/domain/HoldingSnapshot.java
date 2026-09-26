package bank.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "holding_snapshot")
public class HoldingSnapshot {

    @Id
    private UUID id;

    @Column(name = "client_ref", nullable = false, length = 64)
    private String clientRef;

    @Column(name = "account_ref", nullable = false, length = 64)
    private String accountRef;

    @Column(name = "assigned_agent_subject", nullable = false, length = 128)
    private String assignedAgentSubject;

    @Column(name = "instrument_id", nullable = false, length = 32)
    private String instrumentId;

    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 24, scale = 8)
    private BigDecimal unitPrice;

    @Column(name = "valuation_currency", nullable = false, length = 3)
    private String valuationCurrency;

    @Column(name = "fx_to_sgd", nullable = false, precision = 24, scale = 8)
    private BigDecimal fxToSgd;

    @Column(name = "value_sgd", nullable = false, precision = 19, scale = 4)
    private BigDecimal valueSgd;

    @Column(name = "valued_at", nullable = false)
    private Instant valuedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ValuationFreshness freshness;

    @Column(name = "source_version", nullable = false, length = 50)
    private String sourceVersion;

    protected HoldingSnapshot() {
    }

    public HoldingSnapshot(UUID id, String clientRef, String accountRef, String assignedAgentSubject,
                           String instrumentId, BigDecimal quantity, BigDecimal unitPrice,
                           String valuationCurrency, BigDecimal fxToSgd, BigDecimal valueSgd,
                           Instant valuedAt, ValuationFreshness freshness, String sourceVersion) {
        this.id = Objects.requireNonNull(id);
        this.clientRef = Objects.requireNonNull(clientRef);
        this.accountRef = Objects.requireNonNull(accountRef);
        this.assignedAgentSubject = Objects.requireNonNull(assignedAgentSubject);
        this.instrumentId = Objects.requireNonNull(instrumentId);
        this.quantity = Objects.requireNonNull(quantity);
        this.unitPrice = Objects.requireNonNull(unitPrice);
        this.valuationCurrency = Objects.requireNonNull(valuationCurrency);
        this.fxToSgd = Objects.requireNonNull(fxToSgd);
        this.valueSgd = Objects.requireNonNull(valueSgd);
        this.valuedAt = Objects.requireNonNull(valuedAt);
        this.freshness = Objects.requireNonNull(freshness);
        this.sourceVersion = Objects.requireNonNull(sourceVersion);
    }

    public UUID getId() { return id; }
    public String getClientRef() { return clientRef; }
    public String getAccountRef() { return accountRef; }
    public String getAssignedAgentSubject() { return assignedAgentSubject; }
    public String getInstrumentId() { return instrumentId; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public String getValuationCurrency() { return valuationCurrency; }
    public BigDecimal getFxToSgd() { return fxToSgd; }
    public BigDecimal getValueSgd() { return valueSgd; }
    public Instant getValuedAt() { return valuedAt; }
    public ValuationFreshness getFreshness() { return freshness; }
    public String getSourceVersion() { return sourceVersion; }
}
