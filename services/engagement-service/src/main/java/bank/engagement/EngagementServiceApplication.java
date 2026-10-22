package bank.engagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Agents' calendar events, email invites and reminders, and client follow-up
 * drafts (agent-engagement feature plan, ADR-0051 to ADR-0054).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EngagementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EngagementServiceApplication.class, args);
    }
}
