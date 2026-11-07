package bank.clienthealth.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientHealthFindingSchemaTest {

    private static final String SCHEMA_ID =
            "https://scrooge-bank.example/schemas/client-health-finding-v1.schema.json";
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        String schemaData = readResource("/contracts/client-health-finding-v1.schema.json");
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(Collections.singletonMap(SCHEMA_ID, schemaData)));
        schema = registry.getSchema(SchemaLocation.of(SCHEMA_ID));
        schema.initializeValidators();
    }

    @Test
    void acceptsCombinedValidFixture() throws IOException {
        List<com.networknt.schema.Error> errors = validate(
                "/fixtures/client-health-monitor/client-health-finding-v1.valid.json");

        assertTrue(errors.isEmpty(), () -> "Expected valid fixture but found: " + errors);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/fixtures/client-health-monitor/client-health-finding-v1.invalid-empty-signals.json",
        "/fixtures/client-health-monitor/client-health-finding-v1.invalid-agent-id.json",
        "/fixtures/client-health-monitor/client-health-finding-v1.invalid-evidence-shape.json"
    })
    void rejectsInvalidFixtures(String fixture) throws IOException {
        assertFalse(validate(fixture).isEmpty(), () -> "Expected fixture to be rejected: " + fixture);
    }

    private static List<com.networknt.schema.Error> validate(String fixture) throws IOException {
        return schema.validate(
                readResource(fixture),
                InputFormat.JSON,
                context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)));
    }

    private static String readResource(String path) throws IOException {
        try (InputStream input = ClientHealthFindingSchemaTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
