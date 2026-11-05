package bank.alert.report;

import bank.alert.ai.EventDirection;
import bank.alert.ai.EventType;
import bank.alert.ai.PublicEvidence;

import java.util.List;

/**
 * Everything the model is told about an event: the event's type and direction, the public names of the instruments
 * it may affect, and at most five public sources (ADR-0084). No client, account or holding data is here.
 */
public record ReportInput(EventType eventType, EventDirection direction, List<InstrumentRef> instruments,
        List<PublicEvidence> evidence) {

    /** An instrument's public identity. */
    public record InstrumentRef(String instrumentId, String ticker, String issuerName) {}

    public ReportInput {
        instruments = List.copyOf(instruments);
        evidence = List.copyOf(evidence);
        if (instruments.isEmpty()) throw new IllegalArgumentException("a report needs at least one instrument");
        if (evidence.isEmpty()) throw new IllegalArgumentException("a report needs at least one source");
    }
}
