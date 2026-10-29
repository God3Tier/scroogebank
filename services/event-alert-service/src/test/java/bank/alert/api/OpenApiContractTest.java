package bank.alert.api;

import bank.alert.event.AgentEventController;
import bank.alert.event.AgentEventModels;
import bank.alert.portfolio.PortfolioController;
import bank.alert.portfolio.PortfolioReplaceRequest;
import bank.alert.portfolio.PortfolioResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps docs/api/event-driven-alerts-holdings.openapi.yaml honest: every path and method
 * it lists is served by a controller, and the request/response fields it names exist.
 */
class OpenApiContractTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec() throws IOException {
        Path file = Path.of("../../docs/api/event-driven-alerts-holdings.openapi.yaml");
        return new Yaml().load(Files.readString(file));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> specOperations() throws IOException {
        Set<String> operations = new TreeSet<>();
        ((Map<String, Map<String, Object>>) spec().get("paths")).forEach((path, item) -> item.keySet().stream()
                .filter(key -> Set.of("get", "put", "post", "delete").contains(key))
                .forEach(method -> operations.add(method.toUpperCase() + " " + path.replaceAll("\\{[a-zA-Z]+}", "{}"))));
        return operations;
    }

    private static Set<String> controllerOperations(Class<?>... controllers) {
        Set<String> operations = new TreeSet<>();
        for (Class<?> controller : controllers) {
            String base = controller.getAnnotation(RequestMapping.class).value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                add(operations, "GET", base, method.getAnnotation(GetMapping.class) == null ? null : method.getAnnotation(GetMapping.class).value());
                add(operations, "PUT", base, method.getAnnotation(PutMapping.class) == null ? null : method.getAnnotation(PutMapping.class).value());
                add(operations, "POST", base, method.getAnnotation(PostMapping.class) == null ? null : method.getAnnotation(PostMapping.class).value());
            }
        }
        return operations;
    }

    private static void add(Set<String> operations, String verb, String base, String[] paths) {
        if (paths == null) return;
        String suffix = paths.length == 0 ? "" : paths[0];
        operations.add(verb + " " + (base + suffix).replaceAll("\\{[a-zA-Z]+}", "{}"));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> schemaProperties(String name) throws IOException {
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec().get("components")).get("schemas");
        return ((Map<String, Object>) ((Map<String, Object>) schemas.get(name)).get("properties")).keySet().stream()
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> fields(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyDocumentedOperationIsServedAndNoneIsMissing() throws IOException {
        // The per-alert routes share /api/alerts and are documented elsewhere, so compare only the new ones.
        Set<String> served = controllerOperations(PortfolioController.class, AgentEventController.class);

        assertThat(specOperations()).isEqualTo(served);
    }

    @Test
    void schemaFieldsMatchTheJavaTypes() throws IOException {
        assertThat(schemaProperties("PortfolioReplaceRequest")).isEqualTo(fields(PortfolioReplaceRequest.class));
        assertThat(schemaProperties("Portfolio")).isEqualTo(fields(PortfolioResponse.class));
        assertThat(schemaProperties("Position")).isEqualTo(fields(PortfolioReplaceRequest.Position.class));
        assertThat(schemaProperties("EventCard")).isEqualTo(fields(AgentEventModels.EventCard.class));
        assertThat(schemaProperties("AffectedClient")).isEqualTo(fields(AgentEventModels.AffectedClient.class));
        assertThat(schemaProperties("EventDetail")).isEqualTo(fields(AgentEventModels.EventDetail.class));
        assertThat(schemaProperties("ActionRequest")).isEqualTo(fields(AgentEventModels.ActionRequest.class));
        assertThat(schemaProperties("ActionResult")).isEqualTo(fields(AgentEventModels.ActionResult.class));
    }
}
