package bank.dashboard.service;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.Panel;
import bank.dashboard.dto.PanelReason;
import bank.dashboard.exception.UpstreamUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Resolves each panel independently, so one failing upstream costs one panel
 * rather than the page.
 *
 * <p>This is the core of the dashboard's availability story. A panel whose
 * upstream is down and a panel whose upstream is not wired up take the
 * same path and differ only in {@link PanelReason} -- which is what lets
 * panels light up as services land without any change here.
 *
 * <p><strong>No retries.</strong> Consistent with the system's stated
 * resiliency position: a read-only dashboard should surface a failure
 * immediately rather than amplify load against a struggling upstream. The
 * per-task cache already absorbs a brief blip for anyone who loaded the page
 * recently.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PanelAssembler {

    private final DashboardProperties properties;

    /**
     * Runs every supplier concurrently and maps each to a panel.
     *
     * @param suppliers panel name to the call that produces its figures;
     *                  iteration order is preserved in the result
     */
    public Map<String, Panel> assemble(Map<String, Supplier<Object>> suppliers) {
        // One virtual thread per call: these are IO-bound waits on upstream
        // HTTP, so there is no reason to size a pool for them.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // submit() rather than CompletableFuture.supplyAsync(): only a
            // FutureTask honours cancel(true) by interrupting its thread.
            // CompletableFuture.cancel ignores the flag, and the executor is
            // closed below, which waits for every task to end -- so an
            // abandoned call would hold the response open past the budget.
            // The caller's SecurityContext is thread-local, so each task
            // carries a copy: the upstream adapters re-present the caller's
            // token (CurrentUser) from the virtual thread, not this one.
            Map<String, Future<Object>> inFlight = new LinkedHashMap<>();
            suppliers.forEach((name, supplier) -> inFlight.put(name,
                    executor.submit(new DelegatingSecurityContextCallable<>(supplier::get))));

            // The overall budget is measured from when the calls were
            // submitted, not accumulated per call. They run concurrently, so a
            // request cannot take the sum of its slowest upstreams.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.upstream().overallBudgetMs());

            Map<String, Panel> panels = new LinkedHashMap<>();
            inFlight.forEach((name, future) -> panels.put(name, resolve(name, future, deadline)));
            return panels;
        }
    }

    private Panel resolve(String name, Future<Object> future, long deadline) {
        long remainingMs = Math.min(
                properties.upstream().perCallTimeoutMs(),
                TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));

        if (remainingMs <= 0) {
            future.cancel(true);
            return timedOut(name);
        }

        try {
            return Panel.ok(future.get(remainingMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException ex) {
            future.cancel(true);
            return timedOut(name);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Panel.unavailable(PanelReason.UPSTREAM_ERROR);
        } catch (ExecutionException ex) {
            return fromCause(name, ex.getCause());
        }
    }

    private Panel fromCause(String name, Throwable cause) {
        if (cause instanceof UpstreamUnavailableException upstream) {
            // An upstream that answered with an error or could not be reached
            // is an incident, and its message is the only record of why. The
            // other reasons, such as the not-wired-up case when running the
            // dashboard on its own, are expected, so they stay at debug and
            // do not drown the logs.
            if (upstream.reason().isTransient()) {
                log.warn("Panel '{}' unavailable: {}", name, upstream.getMessage());
            } else {
                log.debug("Panel '{}' unavailable: {}", name, upstream.getMessage());
            }
            return Panel.unavailable(upstream.reason());
        }
        if (cause instanceof AccessDeniedException) {
            // The agent path working correctly against an admin-only figure,
            // not a fault.
            return Panel.unavailable(PanelReason.UPSTREAM_FORBIDDEN);
        }
        log.warn("Panel '{}' failed", name, cause);
        return Panel.unavailable(PanelReason.UPSTREAM_ERROR);
    }

    private Panel timedOut(String name) {
        log.warn("Panel '{}' exceeded its budget", name);
        return Panel.unavailable(PanelReason.UPSTREAM_TIMEOUT);
    }
}
