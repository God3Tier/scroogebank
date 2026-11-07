package bank.alert.ingestion;

import bank.alert.config.TavilyProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Direct, bounded Tavily adapter chosen by ADR-0050. */
public final class TavilySearchProvider implements PublicSearchProvider {

    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 429, 500, 502, 503, 504);

    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http;

    public TavilySearchProvider(TavilyProperties properties, ObjectMapper mapper, Clock clock) {
        this(properties.endpoint(), properties.apiKey(), properties.timeout(), properties.maxResponseBytes(),
                mapper, clock, HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
    }

    TavilySearchProvider(URI endpoint, String apiKey, Duration timeout, int maxResponseBytes,
            ObjectMapper mapper, Clock clock, HttpClient http) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.mapper = mapper;
        this.clock = clock;
        this.http = http;
        if (apiKey == null || apiKey.isBlank() || apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("a valid Tavily API key is required");
        }
    }

    @Override
    public List<PublicSearchResult> search(PublicSearchRequest request) {
        byte[] requestBody = encode(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException timedOut) {
            throw new SearchProviderException("PROVIDER_TIMEOUT", true, "Tavily timed out");
        } catch (IOException unreachable) {
            throw new SearchProviderException("PROVIDER_UNAVAILABLE", true, "Tavily could not be reached");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SearchProviderException("PROVIDER_INTERRUPTED", false, "the Tavily call was interrupted");
        }

        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(maxResponseBytes + 1);
            if (bytes.length > maxResponseBytes) {
                throw new SearchProviderException("INVALID_PROVIDER_RESPONSE", false,
                        "Tavily response exceeded the configured size limit");
            }
            return parse(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                    bytes, request);
        } catch (IOException unreadable) {
            throw new SearchProviderException("PROVIDER_UNAVAILABLE", true, "Tavily response could not be read");
        }
    }

    private byte[] encode(PublicSearchRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", request.query());
        body.put("search_depth", "basic");
        body.put("chunks_per_source", 1);
        body.put("max_results", request.maximumResults());
        body.put("topic", "news");
        body.put("start_date", utcDate(request.publishedFrom()).toString());
        // Tavily defines end_date as exclusive, so query through the next UTC
        // date and enforce the exact instant window again on every result.
        body.put("end_date", utcDate(request.publishedTo()).plusDays(1).toString());
        body.put("include_published_date", true);
        body.put("filter_by_published_date", true);
        body.put("include_answer", false);
        body.put("include_raw_content", false);
        body.put("include_images", false);
        body.put("include_favicon", false);
        body.put("auto_parameters", false);
        body.put("safe_search", true);
        body.put("include_domains_mode", "restrict");
        body.putArray("include_domains").addAll(request.approvedDomains().stream()
                .map(mapper.getNodeFactory()::textNode).toList());
        try {
            return mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException impossible) {
            throw new SearchProviderException("PROVIDER_REQUEST_INVALID", false,
                    "Tavily request could not be encoded");
        }
    }

    private List<PublicSearchResult> parse(int status, String contentType, byte[] body,
            PublicSearchRequest request) {
        if (status == 401 || status == 403) {
            throw new SearchProviderException("PROVIDER_ACCESS_DENIED", false, "Tavily rejected the API key");
        }
        if (status == 432 || status == 433) {
            throw new SearchProviderException("PROVIDER_QUOTA_EXCEEDED", false, "Tavily quota was exhausted");
        }
        if (status < 200 || status >= 300) {
            boolean retryable = RETRYABLE_STATUS.contains(status);
            throw new SearchProviderException(retryable ? "PROVIDER_UNAVAILABLE" : "PROVIDER_REJECTED",
                    retryable, "Tavily returned HTTP " + status);
        }
        if (!contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            throw new SearchProviderException("INVALID_PROVIDER_RESPONSE", false,
                    "Tavily response was not JSON");
        }

        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException malformed) {
            throw new SearchProviderException("INVALID_PROVIDER_RESPONSE", false,
                    "Tavily response was malformed JSON");
        }
        JsonNode items = root == null ? null : root.get("results");
        if (items == null || !items.isArray() || items.size() > request.maximumResults()) {
            throw new SearchProviderException("INVALID_PROVIDER_RESPONSE", false,
                    "Tavily response did not satisfy the bounded contract");
        }

        Instant retrievedAt = clock.instant();
        List<PublicSearchResult> results = new ArrayList<>();
        for (JsonNode item : items) {
            PublicSearchResult result = toResult(item, retrievedAt, request);
            if (result != null) results.add(result);
        }
        return List.copyOf(results);
    }

    private static PublicSearchResult toResult(JsonNode item, Instant retrievedAt,
            PublicSearchRequest request) {
        String url = item.path("url").asText("").strip();
        String title = clean(item.path("title").asText(""), 300);
        Instant publishedAt = publishedAt(item.path("published_date").asText(null));
        if (url.isEmpty() || title.isEmpty() || publishedAt == null
                || publishedAt.isBefore(request.publishedFrom()) || publishedAt.isAfter(request.publishedTo())
                || !approvedHttpsUrl(url, request.approvedDomains())) {
            return null;
        }
        String snippet = clean(item.path("content").asText(""), PublicSearchResult.MAX_SNIPPET);
        if (snippet.isEmpty()) snippet = null;
        String id = item.path("id").asText("").strip();
        if (id.isEmpty() || id.length() > 128) id = null;
        try {
            return new PublicSearchResult(id, url, title, publishedAt, retrievedAt,
                    sha256(url + "\n" + title + "\n" + (snippet == null ? "" : snippet)), snippet);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static LocalDate utcDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static boolean approvedHttpsUrl(String value, List<String> approvedDomains) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return approvedDomains.stream().map(domain -> domain.toLowerCase(Locale.ROOT))
                    .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static Instant publishedAt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException notIso) {
            try {
                return ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (DateTimeParseException notRfc1123) {
                return null;
            }
        }
    }

    private static String clean(String value, int maximum) {
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ")
                .replaceAll("\\s+", " ").strip();
        return cleaned.length() > maximum ? cleaned.substring(0, maximum) : cleaned;
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
