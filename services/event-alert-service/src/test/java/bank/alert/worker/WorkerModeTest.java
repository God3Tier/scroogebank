package bank.alert.worker;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerModeTest {

    @ParameterizedTest
    @CsvSource({"WORKER, NONE", "worker, NONE", "API, SERVLET"})
    void onlyAWorkerStartsWithoutAWebServer(String mode, WebApplicationType expected) {
        SpringApplication application = new SpringApplication(WorkerModeTest.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("alert.mode", mode)));

        new WorkerMode().onApplicationEvent(new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(), application, new String[0], environment));

        assertThat(application.getWebApplicationType()).isEqualTo(expected);
    }
}
