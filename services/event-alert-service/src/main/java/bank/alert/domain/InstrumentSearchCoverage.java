package bank.alert.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "instrument_search_coverage")
public class InstrumentSearchCoverage {
    @Id
    private String instrumentId;
    private Instant lastSuccessfulTo;
    private Instant updatedAt;

    protected InstrumentSearchCoverage() {}

    public InstrumentSearchCoverage(String instrumentId, Instant lastSuccessfulTo, Instant updatedAt) {
        this.instrumentId = instrumentId;
        this.lastSuccessfulTo = lastSuccessfulTo;
        this.updatedAt = updatedAt;
    }

    public String getInstrumentId() { return instrumentId; }
    public Instant getLastSuccessfulTo() { return lastSuccessfulTo; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void advanceTo(Instant value, Instant updated) {
        if (lastSuccessfulTo == null || value.isAfter(lastSuccessfulTo)) lastSuccessfulTo = value;
        updatedAt = updated;
    }
}
