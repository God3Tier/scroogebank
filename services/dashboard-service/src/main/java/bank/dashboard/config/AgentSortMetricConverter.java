package bank.dashboard.config;

import bank.dashboard.dto.AgentSortMetric;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code sort} query parameter to its enum.
 *
 * <p>Spring Boot registers {@link Converter} beans into the MVC conversion
 * service, so declaring this is the whole wiring. A rejected value surfaces as
 * a {@code MethodArgumentTypeMismatchException}, which the global handler turns
 * into the contract's error body.
 */
@Component
public class AgentSortMetricConverter implements Converter<String, AgentSortMetric> {

    @Override
    public AgentSortMetric convert(String source) {
        return AgentSortMetric.fromWireValue(source);
    }
}
