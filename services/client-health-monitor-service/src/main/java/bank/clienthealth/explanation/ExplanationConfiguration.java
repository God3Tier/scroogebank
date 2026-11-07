package bank.clienthealth.explanation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClientHealthAiProperties.class)
public class ExplanationConfiguration {
    @Bean TemplateExplanationProvider templateExplanationProvider() { return new TemplateExplanationProvider(); }
    @Bean ExplanationMetricsPort explanationMetricsPort(MeterRegistry registry) {
        return new MicrometerExplanationMetrics(registry);
    }
    @Bean ExplanationSafetyValidator explanationSafetyValidator(ObjectMapper mapper) {
        return new ExplanationSafetyValidator(mapper);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "client-health.ai.enabled", havingValue = "true")
    BedrockRuntimeClient clientHealthBedrockClient() {
        return BedrockRuntimeClient.builder().region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(DefaultCredentialsProvider.create()).build();
    }

    @Bean
    @ConditionalOnProperty(name = "client-health.ai.enabled", havingValue = "true")
    BedrockExplanationProvider bedrockExplanationProvider(BedrockRuntimeClient client,
            ClientHealthAiProperties properties, ExplanationSafetyValidator validator,
            ExplanationMetricsPort metrics, ObjectMapper mapper) {
        return new BedrockExplanationProvider(client, properties, validator, metrics, mapper);
    }

    @Bean @Primary
    ExplanationProvider explanationProvider(ClientHealthAiProperties properties,
            ObjectProvider<BedrockExplanationProvider> bedrock, TemplateExplanationProvider template,
            ExplanationMetricsPort metrics) {
        return new SafeExplanationProvider(properties, bedrock.getIfAvailable(), template, metrics);
    }

}
