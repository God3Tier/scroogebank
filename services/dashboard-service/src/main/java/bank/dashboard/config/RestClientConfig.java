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
     *
     * <p>HttpURLConnection rather than the JDK HttpClient that Spring Boot
     * would pick. In AWS, after about 30 s without a call, the JDK client
     * received user-service's response headers but never finished reading
     * the HTTP/1.1 body, although user-service had sent it in full, so the
     * transactions panel timed out on the first load after a pause
     * (ADR-0033, amended 2026-10-24).
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        Duration perCall = Duration.ofMillis(properties.upstream().perCallTimeoutMs());
        return builder
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(perCall)
                                .withReadTimeout(perCall)))
                .build();
    }
}
