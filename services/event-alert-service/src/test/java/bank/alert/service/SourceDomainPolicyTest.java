package bank.alert.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceDomainPolicyTest {

    private final SourceDomainPolicy policy = new SourceDomainPolicy();

    @Test
    void aNameThatDoesNotExistIsRefusedWithAClearMessage() {
        SourceDomainPolicy strict = new SourceDomainPolicy(domain -> domain.equals("reuters.com"));

        assertThat(strict.normalise("www.Reuters.com")).isEqualTo("reuters.com");
        assertThatThrownBy(() -> strict.normalise("asdfghjkl.com"))
                .isInstanceOfSatisfying(SourceDomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("DOMAIN_NOT_FOUND");
                    assertThat(e.getMessage()).contains("asdfghjkl.com", "spelling");
                });
    }

    @Test
    void internalAndExampleNamesAreRefusedWithoutAskingDns() {
        SourceDomainPolicy neverAsked = new SourceDomainPolicy(domain -> { throw new AssertionError("should not be asked"); });

        for (String name : new String[] {"intranet.local", "server.internal", "shop.test", "site.example", "x.invalid", "a.onion"}) {
            assertThatThrownBy(() -> neverAsked.normalise(name))
                    .isInstanceOfSatisfying(SourceDomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_PUBLIC"));
        }
    }

    @Test
    void addressesAndBareNamesAreStillRefusedAsBadFormat() {
        assertThatThrownBy(() -> policy.normalise("192.168.0.1")).isInstanceOf(SourceDomainException.class);
        assertThatThrownBy(() -> policy.normalise("localhost")).isInstanceOf(SourceDomainException.class);
        assertThatThrownBy(() -> policy.normalise("https://reuters.com/news")).isInstanceOf(SourceDomainException.class);
    }

    @Test
    void theRealLookupSaysNoForAReservedNameAndNeverFailsClosed() {
        // ".invalid" is reserved and can never resolve, so this needs no network; a lookup error would answer "exists".
        assertThat(new DnsDomainLookup().exists("definitely-not-a-site.invalid")).isFalse();
    }

    @Test
    void lowerCasesTrimsAndDropsWwwAndTrailingDot() {
        assertThat(policy.normalise("  WWW.Example.COM. ")).isEqualTo("example.com");
        assertThat(policy.normalise("news.example.co.uk")).isEqualTo("news.example.co.uk");
        assertThat(policy.normalise("xn--bcher-kva.de")).isEqualTo("xn--bcher-kva.de");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "example", "https://example.com", "example.com/news", "example.com:8080",
            "example.com?x=1", "*.example.com", "exa mple.com", "user@example.com", "192.168.1.10",
            "-bad.example.com", "bad-.example.com", "exa_mple.com", ".example.com", "example..com",
            "example.c"})
    void rejectsAnythingThatIsNotAPlainHostName(String input) {
        assertThatThrownBy(() -> policy.normalise(input))
                .isInstanceOfSatisfying(SourceDomainException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("INVALID_FORMAT");
                    assertThat(exception.status().value()).isEqualTo(400);
                });
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> policy.normalise(null)).isInstanceOf(SourceDomainException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reddit.com", "old.reddit.com", "WWW.X.COM", "m.facebook.com", "someone.medium.com",
            "foo.blogspot.com", "youtube.com", "t.me"})
    void rejectsSocialForumAndBlogPlatformsAndTheirSubdomains(String input) {
        assertThatThrownBy(() -> policy.normalise(input))
                .isInstanceOfSatisfying(SourceDomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("BLOCKED_DOMAIN"));
    }

    @Test
    void doesNotBlockALookalikeThatOnlySharesASuffixText() {
        assertThat(policy.normalise("notreddit.com")).isEqualTo("notreddit.com");
        assertThat(policy.normalise("max.com")).isEqualTo("max.com");
    }

    @Test
    void coveredByFindsTheDomainOrItsParent() {
        List<String> approved = List.of("reuters.com", "sgx.com");
        assertThat(SourceDomainPolicy.coveredBy("reuters.com", approved)).isEqualTo("reuters.com");
        assertThat(SourceDomainPolicy.coveredBy("uk.reuters.com", approved)).isEqualTo("reuters.com");
        assertThat(SourceDomainPolicy.coveredBy("notreuters.com", approved)).isNull();
        assertThat(SourceDomainPolicy.coveredBy("example.com", approved)).isNull();
    }
}
