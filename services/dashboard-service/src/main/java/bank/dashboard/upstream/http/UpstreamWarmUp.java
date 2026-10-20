package bank.dashboard.upstream.http;

import bank.dashboard.config.DashboardProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Calls each upstream's health endpoint once at startup, before the task takes
 * traffic, so the first dashboard does not pay the dashboard's own first-call
 * costs: the HTTP client, TLS and JSON code the JVM has not run yet. On AWS
 * these made every call of the first page take 1.5 to 2 seconds, whichever
 * upstream it went to.
 *
 * <p>It reads no data and needs no token: the health endpoints are the ones
 * the load balancer checks. The task takes traffic only after two passing
 * load balancer checks 30 seconds apart, so the warm-up ends well before the
 * first user arrives.
 *
 * <p>It never stops the service starting. An upstream that is not wired up is
 * skipped, and one that is down or slow is logged; each call is bounded by
 * the client's timeouts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UpstreamWarmUp implements ApplicationRunner {

    private final RestClient restClient;
    private final DashboardProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        DashboardProperties.Upstream upstream = properties.upstream();
        Map<String, String> healthUrls = new LinkedHashMap<>();
        addIfWired(healthUrls, "user-service", upstream.userServiceBaseUrl(), "/actuator/health");
        addIfWired(healthUrls, "client-service", upstream.clientServiceBaseUrl(), "/health");
        addIfWired(healthUrls, "account-service", upstream.accountServiceBaseUrl(), "/health");

        // In parallel, as the dashboard's own calls are. Closing the executor
        // waits for every call to end.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            healthUrls.forEach((service, url) -> executor.submit(() -> warm(service, url)));
        }
    }

    private static void addIfWired(Map<String, String> healthUrls, String service, String baseUrl, String path) {
        if (StringUtils.hasText(baseUrl)) {
            healthUrls.put(service, StringUtils.trimTrailingCharacter(baseUrl.trim(), '/') + path);
        }
    }

    private void warm(String service, String url) {
        long startedAt = System.nanoTime();
        String outcome = "ok";
        try {
            // Read as JSON, so the parsing code is warmed as well.
            restClient.get().uri(url).retrieve().toEntity(Map.class);
        } catch (RuntimeException ex) {
            outcome = "failed: " + ex.getMessage();
        }
        log.info("Warm-up: {} took {} ms ({})", service, (System.nanoTime() - startedAt) / 1_000_000, outcome);
    }
}
