package bank.dashboard.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    // Documentation only: declaring the scheme gives Swagger UI an Authorize
    // button. SecurityConfig still decides what each endpoint enforces.
    @Bean
    public OpenAPI dashboardServiceOpenApi() {
        return new OpenAPI().info(new Info()
                        .title("Scrooge CRM - Business Dashboard")
                        .description("""
                                Role-shaped business metrics composed from the services that own \
                                the data. Partial results are normal: each panel reports its own \
                                status, and the HTTP status stays 200 while at least one panel \
                                resolved. Figures are eventually consistent -- generatedAt says \
                                when they were computed. The committed contract is \
                                docs/api/dashboard-api.yaml.""")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Cognito access token")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
