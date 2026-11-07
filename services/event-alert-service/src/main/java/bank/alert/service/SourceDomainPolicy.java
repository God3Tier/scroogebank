package bank.alert.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rules for a search domain an agent adds to one company (ADR-0079): a plain host name,
 * not a social, forum or blog platform. Counting and duplicates are checked by the service.
 */
@Component
public class SourceDomainPolicy {

    public static final int MAX_WEBSITES = 20;

    /** Platforms where anyone can post, so a story from them is not a reported event. */
    static final List<String> BLOCKED = List.of(
            "reddit.com", "x.com", "twitter.com", "facebook.com", "instagram.com", "tiktok.com",
            "youtube.com", "youtu.be", "medium.com", "substack.com", "quora.com", "linkedin.com",
            "t.me", "telegram.org", "pinterest.com", "tumblr.com", "blogspot.com", "wordpress.com",
            "stocktwits.com", "discord.com", "whatsapp.com");

    /** Endings that are only ever used inside a network or for examples, never for a public website. */
    static final Set<String> NOT_PUBLIC_ENDINGS = Set.of("local", "localhost", "localdomain", "internal", "intranet", "lan", "home",
            "corp", "test", "example", "invalid", "onion");

    private final DomainLookup lookup;

    @Autowired
    public SourceDomainPolicy(DomainLookup lookup) {
        this.lookup = lookup;
    }

    /** Without a lookup: for tests that must not need the network. Every well-formed name is taken to exist. */
    public SourceDomainPolicy() {
        this(domain -> true);
    }

    private static final Pattern HOST = Pattern.compile(
            "^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+(xn--[a-z0-9-]{1,59}|[a-z]{2,63})$");

    /** Lower-cases, trims and drops a leading "www."; throws if the input is not a plain host name. */
    public String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            throw SourceDomainException.invalid("INVALID_FORMAT", "enter a domain such as example.com");
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("www.")) value = value.substring(4);
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (!HOST.matcher(value).matches()) {
            throw SourceDomainException.invalid("INVALID_FORMAT",
                    "enter only a domain name such as example.com, without http://, a path, a port or a wildcard");
        }
        String host = value;
        if (BLOCKED.stream().anyMatch(blocked -> covers(blocked, host))) {
            throw SourceDomainException.invalid("BLOCKED_DOMAIN",
                    "social, forum and blog platforms cannot be used as news sources");
        }
        String ending = value.substring(value.lastIndexOf('.') + 1);
        if (NOT_PUBLIC_ENDINGS.contains(ending)) {
            throw SourceDomainException.invalid("NOT_PUBLIC",
                    "enter a public website; names ending in ." + ending + " are not on the internet");
        }
        if (!lookup.exists(value)) {
            throw SourceDomainException.invalid("DOMAIN_NOT_FOUND",
                    "we could not find " + value + ", check the spelling");
        }
        return value;
    }

    /** True when {@code domain} is the existing domain or one of its sub-domains. */
    public static boolean covers(String existing, String domain) {
        return domain.equals(existing) || domain.endsWith("." + existing);
    }

    /** The first already-approved domain that makes {@code domain} redundant, if any. */
    public static String coveredBy(String domain, Collection<String> approved) {
        return approved.stream().filter(existing -> covers(existing, domain)).findFirst().orElse(null);
    }
}
