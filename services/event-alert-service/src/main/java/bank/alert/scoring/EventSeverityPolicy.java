package bank.alert.scoring;

import bank.alert.ai.EventClassification;
import bank.alert.ai.EventDirection;
import bank.alert.ai.EventType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Event severity for the materiality score, from the classified event type and
 * direction only (ADR-0049). It is a fixed table, so the same classification
 * always scores the same, and the model never sets its own severity. A negative
 * event is one step more severe than a neutral one of the same type.
 */
@Component
public class EventSeverityPolicy {

    public static final String VERSION = "severity-v1";
    private static final BigDecimal NEGATIVE_STEP = new BigDecimal("0.10");

    private static final Map<EventType, BigDecimal> BASE = Map.of(
            EventType.REGULATORY, new BigDecimal("0.80"),
            EventType.CREDIT, new BigDecimal("0.80"),
            EventType.CORPORATE_ACTION, new BigDecimal("0.70"),
            EventType.EARNINGS, new BigDecimal("0.60"),
            EventType.LEADERSHIP, new BigDecimal("0.60"),
            EventType.MACRO, new BigDecimal("0.50"),
            EventType.OTHER, new BigDecimal("0.30"));

    public BigDecimal severity(EventClassification classification) {
        BigDecimal severity = BASE.get(classification.eventType());
        if (classification.direction() == EventDirection.NEGATIVE) severity = severity.add(NEGATIVE_STEP);
        return severity.min(BigDecimal.ONE);
    }
}
