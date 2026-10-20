package bank.alert.ingestion;

import bank.alert.config.AgentCoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Calls the AgentCore Gateway's Web Search tool over MCP with an IAM (SigV4)
 * signature, the same call the EDA-D1-01 spike makes (ADR-0031, ADR-0049). It
 * sends only the public query, the approved domains and the date window the
 * provider hands it. The tool is found once with tools/list. It has not run
 * against the real service yet: the field names read from a result are those the
 * spike saw (title, url, publishedDate) plus a snippet, taken from the first of
 * snippet, summary, description or text that is present.
 */
public class HttpAgentCoreGatewayClient implements AgentCoreGatewayClient {

    static final String SIGNING_NAME = "bedrock-agentcore";
    static final String MCP_PROTOCOL_VERSION = "2025-06-18";
    private static final List<String> SNIPPET_FIELDS = List.of("snippet", "summary", "description", "text");
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 425, 429, 500, 502, 503, 504);

    private final URI endpoint;
    private final Region region;
    private final java.time.Duration timeout;
    private final ObjectMapper mapper;
    private final AwsCredentialsProvider credentials;
    private final Clock clock;
    private final HttpClient http;
    private volatile String toolName;

    public HttpAgentCoreGatewayClient(AgentCoreProperties properties, ObjectMapper mapper,
            AwsCredentialsProvider credentials, Clock clock) {
        if (properties.gatewayUrl() == null) {
            throw new IllegalArgumentException("an AgentCore gateway URL is required");
        }
        this.endpoint = URI.create(properties.gatewayUrl().replaceAll("/+$", "") + "/mcp");
        this.region = Region.of(properties.region());
        this.timeout = properties.timeout();
        this.mapper = mapper;
        this.credentials = credentials;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    @Override
    public AgentCoreSearchResponse invoke(AgentCoreSearchPayload payload) {
        JsonNode response = post(callBody(payload));
        if (response.hasNonNull("error")) {
            throw new SearchProviderException("PROVIDER_ERROR", false, "the gateway returned an MCP error");
        }
        return new AgentCoreSearchResponse(results(response));
    }

    // ------------------------------------------------------------- requests

    private ObjectNode callBody(AgentCoreSearchPayload payload) {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("query", payload.query());
        arguments.put("maxResults", payload.maximumResults());
        ObjectNode filters = arguments.putObject("filters");
        ArrayNode include = filters.putObject("domainFilter").putArray("include");
        payload.domains().forEach(include::add);
        ObjectNode dates = filters.putObject("publishedDateFilter");
        dates.put("from", payload.publishedFromUtc());
        dates.put("to", payload.publishedToUtc());

        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", "search");
        body.put("method", "tools/call");
        ObjectNode params = body.putObject("params");
        params.put("name", webSearchTool());
        params.set("arguments", arguments);
        return body;
    }

    private String webSearchTool() {
        String cached = toolName;
        if (cached != null) return cached;
        ObjectNode list = mapper.createObjectNode();
        list.put("jsonrpc", "2.0");
        list.put("id", "list-tools");
        list.put("method", "tools/list");
        list.putObject("params");
        List<String> matches = new ArrayList<>();
        for (JsonNode tool : post(list).path("result").path("tools")) {
            String name = tool.path("name").asText("");
            if (name.replace("_", "").toLowerCase(Locale.ROOT).contains("websearch")) matches.add(name);
        }
        if (matches.size() != 1) {
            throw new SearchProviderException("PROVIDER_TOOL_NOT_FOUND", false,
                    "the gateway does not expose exactly one web search tool");
        }
        toolName = matches.getFirst();
        return toolName;
    }

    private JsonNode post(ObjectNode body) {
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw new SearchProviderException("PROVIDER_REQUEST_INVALID", false, "cannot encode the request");
        }
        SdkHttpFullRequest signed = sign(bytes);
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        signed.headers().forEach((name, values) -> {
            // The JDK client sets these itself and refuses them as explicit headers.
            if (name.equalsIgnoreCase("host") || name.equalsIgnoreCase("content-length")) return;
            values.forEach(value -> request.header(name, value));
        });
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException timedOut) {
            throw new SearchProviderException("PROVIDER_TIMEOUT", true, "the gateway timed out");
        } catch (IOException unreachable) {
            throw new SearchProviderException("PROVIDER_UNAVAILABLE", true, "the gateway could not be reached");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SearchProviderException("PROVIDER_INTERRUPTED", false, "the call was interrupted");
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new SearchProviderException("PROVIDER_ACCESS_DENIED", false, "the gateway refused the IAM identity");
        }
        if (status < 200 || status >= 300) {
            throw new SearchProviderException(RETRYABLE_STATUS.contains(status)
                    ? "PROVIDER_UNAVAILABLE" : "PROVIDER_REJECTED", RETRYABLE_STATUS.contains(status),
                    "the gateway returned HTTP " + status);
        }
        return parse(response.body(), response.headers().firstValue("Content-Type").orElse(""));
    }

    private SdkHttpFullRequest sign(byte[] body) {
        SdkHttpFullRequest unsigned = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(endpoint)
                .putHeader("Accept", "application/json, text/event-stream")
                .putHeader("Content-Type", "application/json")
                .putHeader("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
                .contentStreamProvider(() -> new ByteArrayInputStream(body))
                .build();
        return Aws4Signer.create().sign(unsigned, Aws4SignerParams.builder()
                .awsCredentials(credentials.resolveCredentials())
                .signingName(SIGNING_NAME)
                .signingRegion(region)
                .signingClockOverride(clock)
                .build());
    }

    private JsonNode parse(String raw, String contentType) {
        try {
            if (!contentType.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                JsonNode parsed = mapper.readTree(raw);
                if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("not an object");
                return parsed;
            }
            JsonNode last = null;
            for (String line : raw.split("\\R")) {
                if (line.startsWith("data:")) last = mapper.readTree(line.substring(5).strip());
            }
            if (last == null || !last.isObject()) throw new IllegalArgumentException("no event data");
            return last;
        } catch (IOException | IllegalArgumentException malformed) {
            throw new SearchProviderException("INVALID_PROVIDER_RESPONSE", false,
                    "the gateway response was not valid MCP JSON");
        }
    }

    // -------------------------------------------------------------- results

    private List<PublicSearchResult> results(JsonNode response) {
        List<PublicSearchResult> results = new ArrayList<>();
        Instant retrievedAt = clock.instant();
        for (JsonNode content : response.path("result").path("content")) {
            if (!"text".equals(content.path("type").asText())) continue;
            JsonNode value;
            try {
                value = mapper.readTree(content.path("text").asText("{}"));
            } catch (JsonProcessingException notJson) {
                continue;
            }
            for (JsonNode item : value.path("results")) {
                PublicSearchResult result = toResult(item, retrievedAt);
                if (result != null) results.add(result);
            }
        }
        return results;
    }

    private static PublicSearchResult toResult(JsonNode item, Instant retrievedAt) {
        String url = item.path("url").asText("").strip();
        String title = item.path("title").asText("").strip();
        if (url.isEmpty() || title.isEmpty()) return null;
        if (title.length() > 300) title = title.substring(0, 300);
        String snippet = snippet(item);
        String id = item.path("id").asText("").strip();
        try {
            return new PublicSearchResult(id.isEmpty() || id.length() > 128 ? null : id, url, title,
                    publishedAt(item.path("publishedDate").asText(null)), retrievedAt,
                    sha256(url + "\n" + title + "\n" + (snippet == null ? "" : snippet)), snippet);
        } catch (IllegalArgumentException invalid) {
            return null; // one malformed result must not fail the whole search
        }
    }

    private static String snippet(JsonNode item) {
        for (String field : SNIPPET_FIELDS) {
            String text = item.path(field).asText("").replaceAll("[\\p{Cntrl}&&[^\\n]]", " ")
                    .replaceAll("\\s+", " ").strip();
            if (!text.isEmpty()) {
                return text.length() > PublicSearchResult.MAX_SNIPPET
                        ? text.substring(0, PublicSearchResult.MAX_SNIPPET) : text;
            }
        }
        return null;
    }

    private static Instant publishedAt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException notAnInstant) {
            return null;
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
