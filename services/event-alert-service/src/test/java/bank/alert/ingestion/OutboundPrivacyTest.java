package bank.alert.ingestion;

import bank.alert.ai.AffectedInstrument;
import bank.alert.ai.AiModelRequest;
import bank.alert.config.AiProperties;
import bank.alert.ai.AiSafetyScreen;
import bank.alert.ai.BriefingInput;
import bank.alert.ai.EventClassification;
import bank.alert.ai.EventDirection;
import bank.alert.ai.EventType;
import bank.alert.ai.PublicEvidence;
import bank.alert.ai.RuleFallbackEvaluator;
import bank.alert.ai.SafeBriefingGenerator;
import bank.alert.ai.SafeEventClassifier;
import bank.alert.config.IngestionProperties;
import bank.alert.domain.HoldingSnapshot;
import bank.alert.domain.ValuationFreshness;
import bank.alert.holdings.SearchUniverseSelector;
import bank.alert.operations.OperationalMetrics;
import bank.alert.scoring.ExposureCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan section 8: instrument metadata is the only portfolio-derived input that may reach the search
 * provider or the model. Maintained portfolios carrying recognisable private values drive the real
 * universe selection, query building, the real Tavily client (against a capturing local server) and
 * the real classifier and briefing generator (against a capturing gateway); every byte that would
 * leave is then checked for those values.
 */
class OutboundPrivacyTest {

    private static final Instant NOW = Instant.parse("2026-10-30T02:00:00Z");
    private static final String CLIENT = "c9f3a7d1-5b2e-4c80-9a61-3d7e2f4b8a15";
    private static final String ACCOUNT = "a7c1d0e2-8f34-4b59-b6a7-1e9d5c3f2b40";
    private static final String AGENT = "agent-private-subject-7781";
    private static final String QUANTITY = "7654321";
    private static final String VALUE = "10486419.77";
    private static final List<String> PRIVATE = List.of(CLIENT, ACCOUNT, AGENT, QUANTITY, VALUE, "1.37");

    private HttpServer tavily;
    private final List<String> wireBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startCapturingTavily() throws IOException {
        tavily = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tavily.createContext("/search", exchange -> {
            wireBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + "\n" + exchange.getRequestURI() + "\n" + exchange.getRequestHeaders().entrySet().stream()
                    .filter(entry -> !entry.getKey().equalsIgnoreCase("Authorization"))
                    .map(Object::toString).reduce("", String::concat));
            byte[] reply = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        tavily.start();
    }

    @AfterEach
    void stop() {
        tavily.stop(0);
    }

    @Test
    void searchAndModelRequestsCarryInstrumentMetadataButNothingFromThePortfolio() {
        // A private, concentrated portfolio, plus enough other clients to make the instrument "common".
        List<HoldingSnapshot> book = new ArrayList<>();
        book.add(position(CLIENT, ACCOUNT, AGENT, "ALPHA", QUANTITY, VALUE));
        for (int client = 1; client <= 4; client++) {
            book.add(position(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "agent-other",
                    "BASE", "1000", "1000.00"));
        }
        List<String> universe = new SearchUniverseSelector(properties(), new ExposureCalculator(),
                new OperationalMetrics(new SimpleMeterRegistry())).select(book);
        assertThat(universe).contains("ALPHA");

        // Instrument identifiers become public search terms through the catalogue, as in production.
        List<InstrumentSearchTerms> catalogue = universe.stream()
                .map(id -> new InstrumentSearchTerms(id, id + " Holdings", "Synthetic", List.of(),
                        List.of("earnings"), List.of("sgx.com")))
                .toList();
        List<IssuerSearchQuery> queries = new IssuerQueryBuilder().build(catalogue, List.of("sgx.com"), 4);
        TavilySearchProvider provider = new TavilySearchProvider(
                URI.create("http://127.0.0.1:" + tavily.getAddress().getPort() + "/search"), "tvly-test",
                Duration.ofSeconds(5), 16_384, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                HttpClient.newHttpClient());
        queries.forEach(query -> provider.search(new PublicSearchRequest(query.query(), query.approvedDomains(),
                NOW.minusSeconds(14_400), NOW, 5)));

        List<AiModelRequest> modelRequests = new ArrayList<>();
        PublicEvidence evidence = new PublicEvidence("source-1", "ALPHA Holdings reports earnings",
                "ALPHA Holdings reported higher earnings.", "https://www.sgx.com/news/1", "sgx.com", NOW,
                List.of("ALPHA"));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        new SafeEventClassifier(ai(), (request, timeout) -> {
            modelRequests.add(request);
            return "{}";
        }, new AiSafetyScreen(), new RuleFallbackEvaluator(), new ObjectMapper().findAndRegisterModules(), clock)
                .classify(evidence);
        EventClassification classification = new EventClassification("ALPHA earnings", EventType.EARNINGS,
                EventDirection.POSITIVE, new BigDecimal("0.82"),
                List.of(new AffectedInstrument("ALPHA", "source-1")), List.of("source-1"),
                "global.amazon.nova-2-lite-v1:0", "alert-public-v1", NOW);
        new SafeBriefingGenerator(ai(), (request, timeout) -> {
            modelRequests.add(request);
            return "{}";
        }, new AiSafetyScreen(), new ObjectMapper().findAndRegisterModules(), clock)
                .generate(new BriefingInput(classification, List.of(evidence)));

        // Something really was sent, so the absence below means something.
        assertThat(wireBodies).isNotEmpty().anySatisfy(body -> assertThat(body).contains("ALPHA"));
        assertThat(modelRequests).isNotEmpty().allSatisfy(request ->
                assertThat(request.publicInputJson()).contains("ALPHA"));

        List<String> everythingThatLeft = new ArrayList<>(wireBodies);
        modelRequests.forEach(request -> everythingThatLeft.add(String.join("\n", request.operation(),
                request.model(), request.promptVersion(), request.systemInstruction(),
                request.publicInputJson(), request.outputSchema())));
        for (String sent : everythingThatLeft) {
            assertThat(sent).as("outbound request").doesNotContain(PRIVATE.toArray(new String[0]));
        }
    }

    private static HoldingSnapshot position(String client, String account, String agent, String instrument,
            String quantity, String value) {
        BigDecimal unitPrice = new BigDecimal(value).divide(new BigDecimal(quantity), 2, java.math.RoundingMode.HALF_UP);
        return new HoldingSnapshot(UUID.randomUUID(), client, account, agent, instrument,
                new BigDecimal(quantity), unitPrice, "SGD", BigDecimal.ONE, new BigDecimal(value), NOW,
                ValuationFreshness.FRESH, "privacy-test");
    }

    private static IngestionProperties properties() {
        return new IngestionProperties(false, "tavily", Duration.ofMinutes(5), Duration.ofHours(4), 4, 10, 3,
                Duration.ofDays(90), new BigDecimal("0.20"), 2, new BigDecimal("0.50"),
                List.of("sgx.com"), List.of("earnings"));
    }

    private static AiProperties ai() {
        return new AiProperties(true, "global.amazon.nova-2-lite-v1:0", "alert-public-v1", 12_000,
                8_000, 1_200, 2, Duration.ofSeconds(20), new BigDecimal("0.60"));
    }
}
