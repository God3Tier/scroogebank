package bank.clienthealth.transaction.adapter.http;

import bank.clienthealth.metrics.ClientHealthMetrics;
import bank.clienthealth.transaction.application.TransactionReplayService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "client-health.runtime", name = "mode", havingValue = "ANALYSIS")
@EnableConfigurationProperties(TransactionReplayProperties.class)
public class TransactionReplayHttpConfiguration {
    @Bean
    RestClient transactionReplayRestClient(
            RestClient.Builder builder, TransactionReplayProperties properties
    ) {
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());
        return builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings)).build();
    }

    @Bean
    TransactionReplayTokenProvider transactionReplayTokens(
            RestClient transactionReplayRestClient,
            TransactionReplayProperties properties,
            Clock clock
    ) {
        return new TransactionReplayTokenProvider(transactionReplayRestClient, properties, clock);
    }

    @Bean
    HttpTransactionReplaySource transactionReplaySource(
            RestClient transactionReplayRestClient,
            TransactionReplayTokenProvider transactionReplayTokens,
            TransactionReplayProperties properties,
            ObjectMapper objectMapper
    ) {
        ObjectMapper strictJson = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        return new HttpTransactionReplaySource(
                transactionReplayRestClient, transactionReplayTokens, properties, strictJson);
    }

    @Bean
    TransactionReplayService transactionReplayService(
            HttpTransactionReplaySource source,
            TransactionReplayProperties properties,
            ClientHealthMetrics metrics
    ) {
        return new TransactionReplayService(source, properties.pageSize(),
                properties.maximumPages(), properties.maximumRows(), metrics);
    }
}
