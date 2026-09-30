package bank.alert.ingestion;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class PublicUrlCanonicalizer {

    private static final Set<String> TRACKING_KEYS = Set.of(
            "fbclid", "gclid", "mc_cid", "mc_eid", "ref", "source");

    public CanonicalPublicUrl canonicalize(String value, List<String> approvedDomains) {
        try {
            URI input = new URI(value).normalize();
            String scheme = input.getScheme() == null ? "" : input.getScheme().toLowerCase(Locale.ROOT);
            String host = input.getHost() == null ? "" : input.getHost().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("https") && !scheme.equals("http")) || host.isBlank()
                    || input.getUserInfo() != null) {
                throw new IllegalArgumentException("source URL must be an absolute HTTP(S) URL without user info");
            }
            boolean approved = approvedDomains.stream()
                    .map(domain -> domain.toLowerCase(Locale.ROOT))
                    .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
            if (!approved) throw new IllegalArgumentException("source domain is not approved");

            int port = input.getPort();
            if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
            String path = input.getPath() == null || input.getPath().isBlank() ? "/" : input.getPath();
            String query = canonicalQuery(input.getRawQuery());
            URI canonical = new URI(scheme, null, host, port, path, query, null);
            String canonicalValue = canonical.toASCIIString();
            if (canonicalValue.length() > 2048) throw new IllegalArgumentException("canonical URL exceeds 2048 characters");
            return new CanonicalPublicUrl(canonicalValue, host, sha256(canonicalValue));
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("source URL is malformed", exception);
        }
    }

    private static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        String joined = Arrays.stream(rawQuery.split("&"))
                .filter(part -> {
                    String key = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
                    return !key.startsWith("utm_") && !TRACKING_KEYS.contains(key);
                })
                .sorted(Comparator.naturalOrder())
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        return joined.isBlank() ? null : joined;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
