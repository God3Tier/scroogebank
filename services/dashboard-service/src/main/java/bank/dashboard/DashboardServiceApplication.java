package bank.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Composes role-shaped business metrics from the services that own the data.
 *
 * <p>This service owns no data and has no datasource. Every figure it returns
 * is aggregated by the service that owns the underlying table and fetched with
 * the caller's own token, so the dashboard can read nothing the caller could
 * not already read. See ADR-0033.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DashboardServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DashboardServiceApplication.class, args);
    }
}
