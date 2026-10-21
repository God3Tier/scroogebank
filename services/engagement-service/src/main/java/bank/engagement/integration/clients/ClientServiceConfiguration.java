package bank.engagement.integration.clients;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ClientServiceConfiguration {

    static final String REST_CLIENT = "clientServiceRestClient";

    /** Connect and read timeouts, so a slow client-service cannot hold a request open. */
    @Bean(REST_CLIENT)
    RestClient clientServiceRestClient(RestClient.Builder builder, ClientServiceProperties properties) {
        return builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(properties.timeout())
                                .withReadTimeout(properties.timeout())))
                .build();
    }
}
