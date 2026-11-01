package bank.engagement.integration.sources;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class FollowUpSourceConfiguration {

    static final String REST_CLIENT = "followUpSourceRestClient";

    /** Connect and read timeouts, so a slow source cannot hold a request open. */
    @Bean(REST_CLIENT)
    RestClient followUpSourceRestClient(RestClient.Builder builder, FollowUpSourceProperties properties) {
        return builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(properties.timeout())
                                .withReadTimeout(properties.timeout())))
                .build();
    }
}
