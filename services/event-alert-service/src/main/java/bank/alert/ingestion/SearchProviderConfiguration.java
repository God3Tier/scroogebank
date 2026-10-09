package bank.alert.ingestion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SearchProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean(PublicSearchProvider.class)
    PublicSearchProvider unconfiguredPublicSearchProvider() {
        return request -> {
            throw new SearchProviderException("PROVIDER_NOT_CONFIGURED", false,
                    "no approved public-search provider client is configured");
        };
    }
}
