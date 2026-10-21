package bank.clienthealth.transaction.contract;

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

class TransactionReplayPageSchemaTest {
    private static final String SCHEMA_ID =
            "https://scrooge-bank.example/schemas/transaction-replay-page-v1.schema.json";
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        String schemaData = read("/contracts/transaction-replay-page-v1.schema.json");
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(Collections.singletonMap(SCHEMA_ID, schemaData)));
        schema = registry.getSchema(SchemaLocation.of(SCHEMA_ID));
        schema.initializeValidators();
    }

    @Test
    void acceptsValidMatchedTransactionPage() throws IOException {
        assertTrue(validate("/fixtures/client-health-monitor/transaction-replay-page-v1.valid.json").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/fixtures/client-health-monitor/transaction-replay-page-v1.invalid-unmatched.json",
        "/fixtures/client-health-monitor/transaction-replay-page-v1.invalid-description.json"
    })
    void rejectsUnsafeProviderPages(String fixture) throws IOException {
        assertFalse(validate(fixture).isEmpty(), () -> "Expected fixture to be rejected: " + fixture);
    }

    private static List<com.networknt.schema.Error> validate(String fixture) throws IOException {
        return schema.validate(
                read(fixture),
                InputFormat.JSON,
                context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)));
    }

    private static String read(String path) throws IOException {
        try (InputStream input = TransactionReplayPageSchemaTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
