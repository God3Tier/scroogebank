package bank.dashboard.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final DashboardProperties properties;

    /**
     * Timeouts are set on the client as well as enforced by the panel
     * assembler's deadline. Belt and braces on purpose: the assembler's budget
     * stops a slow upstream delaying the response, but only a socket timeout
     * stops the abandoned call holding a connection open behind it.
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        Duration perCall = Duration.ofMillis(properties.upstream().perCallTimeoutMs());
        return builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(perCall)
                                .withReadTimeout(perCall)))
                .build();
    }
}
