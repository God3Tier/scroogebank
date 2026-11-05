package bank.alert.config;

import bank.alert.operations.OperationalMetrics;
import bank.alert.crm.CrmDirectory;
import bank.alert.crm.CrmDirectoryProperties;
import bank.alert.crm.HttpCrmDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({
        AlertModeProperties.class,
        IngestionProperties.class,
        SearchFilterProperties.class,
        AiProperties.class,
        BedrockAiProperties.class,
        AnthropicAiProperties.class,
        OperationalProperties.class,
        ReplayProperties.class,
        ResearchProperties.class,
        SearchOnAddProperties.class,
        ScheduleProperties.class,
        TavilyProperties.class,
        PipelineProperties.class,
        CrmDirectoryProperties.class
})
public class AlertServiceConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    /**
     * Database mode needs the CRM directory; incomplete configuration stops start-up
     * rather than falling back to fixture clients.
     */
    @Bean
    @ConditionalOnProperty(name = "alert.holdings.mode", havingValue = "database")
    CrmDirectory crmDirectory(CrmDirectoryProperties properties, Clock clock, OperationalMetrics metrics) {
        return new HttpCrmDirectory(properties, clock, metrics::directoryFailure);
    }
}
