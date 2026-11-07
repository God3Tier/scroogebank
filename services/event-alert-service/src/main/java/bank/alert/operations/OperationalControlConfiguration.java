package bank.alert.operations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OperationalControlConfiguration {
    @Bean
    @ConditionalOnMissingBean(OperationalControlAuditSink.class)
    OperationalControlAuditSink unavailableOperationalControlAuditSink() {
        return event -> { throw new OperationalControlAuditUnavailableException(); };
    }
}
