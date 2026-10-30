package bank.dashboard.contract;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Checks every response from every test's MockMvc against the service's
 * OpenAPI contracts (ADR-0063). Listed in the AutoConfigureMockMvc imports
 * file under src/test/resources, so no test opts in or out.
 */
@AutoConfiguration
public class OpenApiContractConfiguration {

    @Bean
    MockMvcBuilderCustomizer openApiContract() {
        OpenApiContract contract = new OpenApiContract();
        return builder -> builder.alwaysExpect(contract);
    }
}
