package bank.alert.crm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The real HTTP client against a stub token endpoint and stub directory routes. */
class HttpCrmDirectoryTest {

    private static final UUID CLIENT = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("a0000000-0000-4000-8000-000000000001");

    private HttpServer server;
    private String base;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<Integer> batchSizes = new CopyOnWriteArrayList<>();
    private volatile int tokenCalls;
    private volatile int accountStatus = 200;
    private volatile int tokenTtl = 3600;
    private volatile String accountBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        accountBody = "{\"items\":[{\"id\":\"" + ACCOUNT + "\",\"client_id\":\"" + CLIENT
                + "\",\"type\":\"INVESTMENT\",\"status\":\"ACTIVE\",\"currency\":\"SGD\","
                + "\"owner_agent_id\":\"agent-a\"}]}";
        server.createContext("/token", exchange -> {
            tokenCalls++;
            respond(exchange, 200, "{\"access_token\":\"machine-token-" + tokenCalls + "\",\"expires_in\":" + tokenTtl + "}");
        });
        server.createContext("/accounts", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            batchSizes.add(request.split(",", -1).length);
            respond(exchange, accountStatus, accountBody);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private HttpCrmDirectory directory(Clock clock) {
        return new HttpCrmDirectory(new CrmDirectoryProperties(base + "/accounts",
                base + "/token", "alert-worker", "not-a-real-secret", "crm-accounts/alert-directory",
                Duration.ofSeconds(2)), clock);
    }

    @Test
    void resolvesAccountsWithTheirOwnerUsingAMachineTokenAndReusesItUntilExpiry() {
        HttpCrmDirectory directory = directory(Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC));

        var accounts = directory.accounts(List.of(ACCOUNT));
        directory.accounts(List.of(ACCOUNT));

        assertThat(accounts.get(ACCOUNT).activeInvestment()).isTrue();
        assertThat(accounts.get(ACCOUNT).clientId()).isEqualTo(CLIENT);
        assertThat(accounts.get(ACCOUNT).ownedBy("agent-a")).isTrue();
        assertThat(accounts.get(ACCOUNT).ownedBy("agent-b")).isFalse();
        assertThat(authorizations).containsExactly("Bearer machine-token-1", "Bearer machine-token-1");
        assertThat(tokenCalls).isOne();
    }

    @Test
    void fetchesANewTokenOnceTheOldOneIsAboutToExpire() {
        tokenTtl = 10; // inside the 30 s safety margin: never reused
        HttpCrmDirectory directory = directory(Clock.systemUTC());

        directory.accounts(List.of(ACCOUNT));
        directory.accounts(List.of(ACCOUNT));

        assertThat(tokenCalls).isEqualTo(2);
    }

    @Test
    void splitsLargeLookupsIntoBatchesOfAtMostTwoHundredDistinctIds() {
        List<UUID> ids = new ArrayList<>(IntStream.range(0, 450).mapToObj(i -> UUID.randomUUID()).toList());
        ids.add(ids.getFirst()); // a repeat is sent once

        directory(Clock.systemUTC()).accounts(ids);

        assertThat(batchSizes).hasSize(3);
        assertThat(batchSizes.stream().mapToInt(Integer::intValue).max().orElseThrow()).isLessThanOrEqualTo(200);
    }

    @Test
    void anUnreachableOrMalformedCrmIsUnavailableNeverEmpty() {
        accountStatus = 503;
        assertThatThrownBy(() -> directory(Clock.systemUTC()).accounts(List.of(ACCOUNT)))
                .isInstanceOf(DirectoryUnavailableException.class);

        accountStatus = 200;
        accountBody = "{\"unexpected\":true}";
        assertThatThrownBy(() -> directory(Clock.systemUTC()).accounts(List.of(ACCOUNT)))
                .isInstanceOf(DirectoryUnavailableException.class);
    }

    @Test
    void requiresCompleteHttpsConfigurationAndNeverDowngradesToHttp() {
        assertThatThrownBy(() -> new HttpCrmDirectory(
                new CrmDirectoryProperties("", "", "", "", "", null), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ALERT_CRM_");
        assertThatThrownBy(() -> new HttpCrmDirectory(
                new CrmDirectoryProperties("http://crm.internal/a",
                        "https://idp.example/token", "id", "secret", "scope", null), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
    }
}
