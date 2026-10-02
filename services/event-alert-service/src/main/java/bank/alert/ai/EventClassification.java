package bank.alert.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record EventClassification(String headline, EventType eventType, EventDirection direction,
        BigDecimal confidence, List<AffectedInstrument> affectedInstruments,
        List<String> evidenceRefs, String modelVersion, String promptVersion,
        Instant classifiedAt) {
    public EventClassification {
        affectedInstruments = List.copyOf(affectedInstruments);
        evidenceRefs = List.copyOf(evidenceRefs);
    }
}
