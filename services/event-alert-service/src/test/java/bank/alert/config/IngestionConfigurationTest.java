package bank.alert.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionConfigurationTest {

    @Test
    void keepsEveryControlledEventTermAsItsOwnYamlListItem() throws IOException {
        Map<String, Object> root;
        FileSystemResource configuration = new FileSystemResource(
                System.getProperty("basedir") + "/src/main/resources/application.yml");
        try (InputStream input = configuration.getInputStream()) {
            root = new Yaml().load(input);
        }
        Map<?, ?> alert = (Map<?, ?>) root.get("alert");
        Map<?, ?> ingestion = (Map<?, ?>) alert.get("ingestion");

        assertThat(ingestion.get("event-terms"))
                .isEqualTo(List.of("earnings", "results", "financial results", "profit", "revenue",
                        "guidance", "regulatory action", "investigation", "fine", "filing", "approval",
                        "merger", "acquisition", "takeover", "divestment", "buyback", "credit rating",
                        "downgrade", "upgrade", "default", "credit outlook", "appointment", "appointed",
                        "appoints", "resignation", "resigned", "steps down"));
    }
}
