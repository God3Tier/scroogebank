package bank.alert.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks DNS whether the domain exists, so a made-up name such as "asdfgh.com" is refused when an agent adds it. Only an answer of
 * "no such host" counts as not existing: if the lookup times out or fails for another reason it says yes, so a DNS problem on our
 * side does not stop agents adding real sites.
 */
@Component
public class DnsDomainLookup implements DomainLookup {

    private static final Logger log = LoggerFactory.getLogger(DnsDomainLookup.class);
    private static final long TIMEOUT_SECONDS = 3;

    @Override
    public boolean exists(String domain) {
        try {
            return CompletableFuture.supplyAsync(() -> resolves(domain)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException slow) {
            log.warn("domain lookup timed out, allowing the domain");
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Exception failed) {
            log.warn("domain lookup failed error_code={}, allowing the domain", failed.getClass().getSimpleName());
            return true;
        }
    }

    private static boolean resolves(String domain) {
        try {
            return InetAddress.getAllByName(domain).length > 0;
        } catch (UnknownHostException none) {
            return false;
        }
    }
}
