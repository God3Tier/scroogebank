package bank.clienthealth.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ReadinessDatabaseIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        values.add("spring.datasource.username", POSTGRES::getUsername);
        values.add("spring.datasource.password", POSTGRES::getPassword);
        values.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "https://cognito.test/pool");
        values.add("idp.cognito.app-client-id", () -> "crm-app-client");
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired MockMvc mockMvc;

    @Test
    void readinessTurnsDownWhenHealthDatabaseIsUnavailable() throws Exception {
        assertThat(mockMvc.perform(get("/actuator/health/readiness"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        POSTGRES.stop();
        int status = 200;
        for (int attempt = 0; attempt < 10 && status == 200; attempt++) {
            status = mockMvc.perform(get("/actuator/health/readiness"))
                    .andReturn().getResponse().getStatus();
            if (status == 200) Thread.sleep(200);
        }
        assertThat(status).isEqualTo(503);
    }
}
