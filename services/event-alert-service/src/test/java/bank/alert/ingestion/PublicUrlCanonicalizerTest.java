package bank.alert.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicUrlCanonicalizerTest {

    private final PublicUrlCanonicalizer canonicalizer = new PublicUrlCanonicalizer();

    @Test
    void removesFragmentsTrackingAndDefaultPortsDeterministically() {
        CanonicalPublicUrl first = canonicalizer.canonicalize(
                "HTTPS://www.sgx.com:443/news/item?utm_source=test&b=2&a=1#section",
                List.of("sgx.com"));
        CanonicalPublicUrl second = canonicalizer.canonicalize(
                "https://www.sgx.com/news/item?a=1&b=2", List.of("sgx.com"));

        assertThat(first.value()).isEqualTo("https://www.sgx.com/news/item?a=1&b=2");
        assertThat(first.sha256()).isEqualTo(second.sha256()).hasSize(64);
    }

    @Test
    void rejectsUnapprovedDomainsAndEmbeddedCredentials() {
        assertThatThrownBy(() -> canonicalizer.canonicalize(
                "https://publisher.invalid/story", List.of("sgx.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not approved");
        assertThatThrownBy(() -> canonicalizer.canonicalize(
                "https://user:secret@sgx.com/story", List.of("sgx.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without user info");
    }
}
