package bank.clienthealth.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ClientHealthRuntimeModeTest {
    @ParameterizedTest
    @CsvSource({"ANALYSIS, NONE", "analysis, NONE", "API, SERVLET"})
    void onlyAnalysisModeDisablesTheWebServer(String mode, WebApplicationType expected) {
        SpringApplication application = new SpringApplication(ClientHealthRuntimeModeTest.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "test", Map.of("client-health.runtime.mode", mode)));

        new ClientHealthRuntimeMode().onApplicationEvent(new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(), application, new String[0], environment));

        assertThat(application.getWebApplicationType()).isEqualTo(expected);
    }
}
