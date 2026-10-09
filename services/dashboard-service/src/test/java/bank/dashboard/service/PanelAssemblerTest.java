package bank.dashboard.service;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.Panel;
import bank.dashboard.dto.PanelReason;
import bank.dashboard.dto.PanelStatus;
import bank.dashboard.exception.UpstreamUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class PanelAssemblerTest {

    private final PanelAssembler assembler = new PanelAssembler(
            new DashboardProperties(
                    new DashboardProperties.Window(30, 365),
                    new DashboardProperties.Cache(60, 1000),
                    new DashboardProperties.Upstream(200, 500, "", "", "")));

    @Test
    void oneFailingUpstreamCostsOnePanelNotThePage() {
        Map<String, Supplier<Object>> suppliers = new LinkedHashMap<>();
        suppliers.put("clients", () -> "client figures");
        suppliers.put("transactions", () -> {
            throw new UpstreamUnavailableException(PanelReason.UPSTREAM_NOT_DEPLOYED, "not built yet");
        });

        Map<String, Panel> panels = assembler.assemble(suppliers);

        assertThat(panels.get("clients").status()).isEqualTo(PanelStatus.OK);
        assertThat(panels.get("clients").data()).isEqualTo("client figures");
        assertThat(panels.get("transactions").status()).isEqualTo(PanelStatus.UNAVAILABLE);
        assertThat(panels.get("transactions").data()).isNull();
        assertThat(panels.get("transactions").reason()).isEqualTo(PanelReason.UPSTREAM_NOT_DEPLOYED);
    }

    @Test
    void distinguishesNotDeployedFromOtherFailures() {
        // A client needs to tell "this has not been built" from "this is
        // having a bad day" without parsing prose, so the reasons must not
        // collapse into one.
        Map<String, Supplier<Object>> suppliers = new LinkedHashMap<>();
        suppliers.put("notBuilt", () -> {
            throw new UpstreamUnavailableException(PanelReason.UPSTREAM_NOT_DEPLOYED, "absent");
        });
        suppliers.put("broken", () -> {
            throw new IllegalStateException("upstream returned nonsense");
        });
        suppliers.put("refused", () -> {
            throw new AccessDeniedException("admin only");
        });

        Map<String, Panel> panels = assembler.assemble(suppliers);

        assertThat(panels.get("notBuilt").reason()).isEqualTo(PanelReason.UPSTREAM_NOT_DEPLOYED);
        assertThat(panels.get("broken").reason()).isEqualTo(PanelReason.UPSTREAM_ERROR);
        assertThat(panels.get("refused").reason()).isEqualTo(PanelReason.UPSTREAM_FORBIDDEN);
    }

    @Test
    void aSlowUpstreamTimesOutWithoutTakingTheOthersDown() {
        Map<String, Supplier<Object>> suppliers = new LinkedHashMap<>();
        suppliers.put("slow", () -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "too late";
        });
        suppliers.put("fast", () -> "in time");

        long startedAt = System.nanoTime();
        Map<String, Panel> panels = assembler.assemble(suppliers);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(panels.get("slow").status()).isEqualTo(PanelStatus.UNAVAILABLE);
        assertThat(panels.get("slow").reason()).isEqualTo(PanelReason.UPSTREAM_TIMEOUT);
        assertThat(panels.get("fast").status()).isEqualTo(PanelStatus.OK);

        // The timeout must bound the response, not only the panel verdict. The
        // abandoned call has to be interrupted and the executor has to stop
        // waiting on it; otherwise this returns after the full 2s sleep while
        // still reporting UPSTREAM_TIMEOUT, which is what it did before.
        assertThat(elapsedMs)
                .describedAs("assemble() must return on the budget, not on the slowest upstream")
                .isLessThan(1_500);
    }

    @Test
    void callsRunConcurrentlySoTheBudgetIsNotTheSumOfThem() {
        // Three upstreams each taking 150ms must not take 450ms. If they ran
        // serially the 500ms overall budget would be exhausted and the last
        // panel would time out.
        Map<String, Supplier<Object>> suppliers = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            String name = "panel" + i;
            suppliers.put(name, () -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return name;
            });
        }

        long startedAt = System.nanoTime();
        Map<String, Panel> panels = assembler.assemble(suppliers);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(panels.values()).allSatisfy(p -> assertThat(p.status()).isEqualTo(PanelStatus.OK));
        assertThat(elapsedMs).isLessThan(450);
    }

    @Test
    void preservesPanelOrder() {
        Map<String, Supplier<Object>> suppliers = new LinkedHashMap<>();
        suppliers.put("first", () -> 1);
        suppliers.put("second", () -> 2);
        suppliers.put("third", () -> 3);

        assertThat(assembler.assemble(suppliers).keySet())
                .containsExactly("first", "second", "third");
    }
}
