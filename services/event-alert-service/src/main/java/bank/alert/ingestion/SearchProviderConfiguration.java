package bank.alert.ingestion;

import bank.alert.config.TavilyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class SearchProviderConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "alert.ingestion", name = "provider", havingValue = "tavily")
    @ConditionalOnExpression("'${alert.ingestion.tavily.api-key:}' != ''")
    PublicSearchProvider tavilySearchProvider(TavilyProperties properties, ObjectMapper mapper, Clock clock) {
        return new TavilySearchProvider(properties, mapper, clock);
    }

    @Bean
    @ConditionalOnMissingBean(PublicSearchProvider.class)
    PublicSearchProvider publicSearchProvider() {
        return request -> {
            throw new SearchProviderException("PROVIDER_NOT_CONFIGURED", false,
                    "Tavily is not configured; set ALERT_TAVILY_API_KEY only in an approved runtime");
        };
    }
}
