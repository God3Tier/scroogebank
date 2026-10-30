package bank.engagement.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.MockMvcResponse;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Checks this service's HTTP API against its OpenAPI contracts in
 * {@code docs/api} (ADR-0063). Every MockMvc response is checked: a route,
 * method or status the contracts do not list fails, and so does a body that
 * does not match its schema. Requests are not checked, so a test may still
 * send bad input on purpose.
 */
public final class OpenApiContract implements ResultMatcher {

    /** The contracts this service serves, in docs/api. */
    private static final List<String> SPECS = List.of("engagement-api.yaml");

    private static final String PATH_MISSING = "validation.request.path.missing";
    private static final String OPERATION_MISSING = "validation.request.operation.notAllowed";
    private static final String BODY_MISSING = "validation.response.body.missing";

    /** Operational routes, which are not part of any API contract. */
    private static final List<String> OUTSIDE_CONTRACT = List.of("/actuator", "/v3/api-docs", "/swagger-ui");

    private final List<OpenApiInteractionValidator> validators;

    public OpenApiContract() {
        Path apiDir = apiDirectory();
        this.validators = SPECS.stream()
                .map(spec -> OpenApiInteractionValidator
                        .createForSpecificationUrl(apiDir.resolve(spec).toUri().toString())
                        .build())
                .toList();
    }

    @Override
    public void match(MvcResult result) {
        String path = result.getRequest().getRequestURI();
        if (OUTSIDE_CONTRACT.stream().anyMatch(path::startsWith)) {
            return;
        }
        Request.Method method = Request.Method.valueOf(result.getRequest().getMethod());
        String call = method + " " + path + " -> " + result.getResponse().getStatus();
        ValidationReport report = validate(path, method, MockMvcResponse.of(result.getResponse()))
                .orElseThrow(() -> new AssertionError(call + ": the path is in none of " + SPECS));
        // MockMvc does not run the servlet container's error page, so an error
        // sent with sendError has no body here, though it has one deployed.
        boolean bodyOptional = result.getResponse().getStatus() >= 400;
        List<String> errors = report.getMessages().stream()
                .filter(message -> message.getLevel() == ValidationReport.Level.ERROR)
                .filter(message -> !(bodyOptional && BODY_MISSING.equals(message.getKey())))
                .map(message -> "  - " + message.getMessage())
                .toList();
        if (!errors.isEmpty()) {
            throw new AssertionError(call + " breaks the OpenAPI contract:\n" + String.join("\n", errors));
        }
    }

    /**
     * A canned reply from another service, checked against that service's
     * contract before the code under test reads it: this service's side of
     * the contract (ADR-0063). Use it for normal replies only; a test of how
     * the code copes with a broken reply sends one on purpose.
     */
    public static ResponseCreator upstream(String spec, ResponseCreator reply) {
        OpenApiInteractionValidator validator = OpenApiInteractionValidator
                .createForSpecificationUrl(apiDirectory().resolve(spec).toUri().toString())
                .build();
        return request -> {
            ClientHttpResponse created = reply.createResponse(request);
            byte[] body = created.getBody().readAllBytes();
            SimpleResponse.Builder checked = SimpleResponse.Builder.status(created.getStatusCode().value())
                    .withBody(body);
            created.getHeaders().forEach(checked::withHeader);
            String call = request.getMethod() + " " + request.getURI().getPath();
            ValidationReport report = validator.validateResponse(request.getURI().getPath(),
                    Request.Method.valueOf(request.getMethod().name()), checked.build());
            if (report.hasErrors()) {
                throw new AssertionError(call + " in " + spec + ": the canned reply breaks the contract:\n"
                        + report.getMessages().stream()
                                .map(message -> "  - " + message.getMessage())
                                .collect(Collectors.joining("\n")));
            }
            MockClientHttpResponse copy = new MockClientHttpResponse(body, created.getStatusCode());
            copy.getHeaders().putAll(created.getHeaders());
            return copy;
        };
    }

    /**
     * Fails unless every route of every controller under the package is in a
     * contract, including routes no test calls.
     */
    public void assertListsEveryRoute(String basePackage) {
        List<String> missing = new ArrayList<>();
        for (Route route : routes(basePackage)) {
            // A 200 the route may never send: only an absent path or method
            // matters here.
            Optional<ValidationReport> report = validate(route.path(), route.method(), SimpleResponse.Builder.ok().build());
            if (report.isEmpty() || report.get().getMessages().stream()
                    .anyMatch(message -> OPERATION_MISSING.equals(message.getKey()))) {
                missing.add(route.method() + " " + route.path());
            }
        }
        if (!missing.isEmpty()) {
            throw new AssertionError("Routes in none of " + SPECS + ":\n  - " + String.join("\n  - ", missing));
        }
    }

    /** The report from the contract that lists the path, if any does. */
    private Optional<ValidationReport> validate(String path, Request.Method method,
                                                Response response) {
        // A service may serve routes from several contracts; the one that
        // lists the path judges the response.
        return validators.stream()
                .map(validator -> validator.validateResponse(path, method, response))
                .filter(report -> report.getMessages().stream()
                        .noneMatch(message -> PATH_MISSING.equals(message.getKey())))
                .findFirst();
    }

    private record Route(Request.Method method, String path) {
    }

    private static List<Route> routes(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Route> routes = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
            Class<?> controller = ClassUtils.resolveClassName(candidate.getBeanClassName(), null);
            RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            List<String> prefixes = root == null || root.path().length == 0 ? List.of("") : List.of(root.path());
            for (Method handler : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(handler, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                if (mapping.method().length == 0) {
                    throw new IllegalStateException(handler + " answers every method; name the ones it serves");
                }
                List<String> suffixes = mapping.path().length == 0 ? List.of("") : List.of(mapping.path());
                for (String prefix : prefixes) {
                    for (String suffix : suffixes) {
                        for (RequestMethod method : mapping.method()) {
                            // Drop a variable's pattern: {id:\d+} is {id}.
                            String path = (prefix + suffix).replaceAll("\\{(\\w+):[^}]*}", "{$1}");
                            routes.add(new Route(Request.Method.valueOf(method.name()), path));
                        }
                    }
                }
            }
        }
        return routes;
    }

    /** {@code docs/api}, found from the module directory Maven runs tests in. */
    private static Path apiDirectory() {
        Optional<Path> dir = Optional.of(Path.of("").toAbsolutePath());
        while (dir.isPresent()) {
            Path candidate = dir.get().resolve("docs/api");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = Optional.ofNullable(dir.get().getParent());
        }
        throw new IllegalStateException("docs/api not found above " + Path.of("").toAbsolutePath());
    }
}
