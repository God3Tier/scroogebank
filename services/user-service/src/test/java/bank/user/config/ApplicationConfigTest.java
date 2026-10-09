package bank.user.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the real application.yml, which the other tests never read (the test
 * resources' copy replaces it), the way Spring Boot loads it at startup.
 */
class ApplicationConfigTest {

    @Test
    void loadsAndKeepsEveryAuditorSetting() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new FileSystemResource("src/main/resources/application.yml"));

        assertThat(sources).hasSize(1);
        PropertySource<?> config = sources.getFirst();
        assertThat(config.getProperty("auditor.base-url")).isNotNull();
        assertThat(config.getProperty("auditor.grpc-target")).isNotNull();
        assertThat(config.getProperty("auditor.grpc-tls-ca")).isNotNull();
    }
}
