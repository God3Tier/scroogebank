package bank.alert.ai;

import java.math.BigDecimal;
import java.util.List;

public record ClassificationModelOutput(Boolean abstain, String abstentionReason,
        String headline, EventType eventType, EventDirection direction, BigDecimal confidence,
        List<AffectedInstrument> affectedInstruments, List<String> evidenceRefs) { }
