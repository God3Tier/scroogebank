package bank.alert.holdings;

import bank.alert.config.IngestionProperties;
import bank.alert.ingestion.SearchUniverseProvider;
import bank.alert.operations.OperationalMetrics;
import bank.alert.scoring.ExposureCalculator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Search universe from the maintained portfolios. Only instrument identifiers leave
 * this class; the commonality denominator is distinct CRM clients with a positive
 * holding, not every CRM user.
 */
@Component
@ConditionalOnProperty(name = "alert.holdings.mode", havingValue = "database")
public class DatabaseSearchUniverseProvider implements SearchUniverseProvider {

    private final CurrentHoldingsReader reader;
    private final SearchUniverseSelector selector;
    private final Clock clock;

    public DatabaseSearchUniverseProvider(CurrentHoldingsReader reader, IngestionProperties properties,
            ExposureCalculator exposureCalculator, OperationalMetrics metrics, Clock clock) {
        this.reader = reader;
        this.selector = new SearchUniverseSelector(properties, exposureCalculator, metrics);
        this.clock = clock;
    }

    @Override
    public List<String> eligibleInstrumentIds() {
        return selector.select(reader.read(clock.instant(), null, null).stream()
                .flatMap(portfolio -> portfolio.positions().stream()).toList());
    }
}
