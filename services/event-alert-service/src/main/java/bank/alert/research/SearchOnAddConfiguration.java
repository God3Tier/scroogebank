package bank.alert.research;

import bank.alert.config.SearchOnAddProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.ecs.EcsClient;

/** Chooses how a search is started when an agent adds a domain: off (no bean), ecs, or local-process. */
@Configuration
public class SearchOnAddConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "alert.search-on-add", name = "mode", havingValue = "ecs")
    SearchOnAddTrigger ecsSearchOnAddTrigger(SearchOnAddProperties properties) {
        if (blank(properties.cluster()) || blank(properties.taskDefinition())
                || properties.subnets().isEmpty() || blank(properties.securityGroup())) {
            throw new IllegalStateException("ecs search-on-add needs a cluster, task definition, subnets and security group");
        }
        return new EcsSearchOnAddTrigger(EcsClient.create(), properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "alert.search-on-add", name = "mode", havingValue = "local-process")
    SearchOnAddTrigger localProcessSearchOnAddTrigger(SearchOnAddProperties properties) {
        return new LocalProcessSearchOnAddTrigger(properties);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
