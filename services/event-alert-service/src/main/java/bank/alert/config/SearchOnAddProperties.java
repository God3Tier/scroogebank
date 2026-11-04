package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * What happens when an agent adds a search domain (ADR-0080): the API asks for one targeted
 * search of that company, over the last {@code lookback}, using only the new domain.
 *
 * {@code mode} is {@code off} (the default), {@code ecs} (start the worker task in AWS) or
 * {@code local-process} (start the worker as a child process, for a developer's machine only).
 */
@ConfigurationProperties(prefix = "alert.search-on-add")
public record SearchOnAddProperties(
        String mode,
        Duration cooldown,
        int dailyCap,
        Duration lookback,
        String cluster,
        String taskDefinition,
        String containerName,
        List<String> subnets,
        String securityGroup,
        List<String> command) {

    public SearchOnAddProperties {
        mode = mode == null || mode.isBlank() ? "off" : mode.strip().toLowerCase();
        cooldown = cooldown == null ? Duration.ofMinutes(15) : cooldown;
        dailyCap = dailyCap <= 0 ? 20 : dailyCap;
        lookback = lookback == null ? Duration.ofDays(14) : lookback;
        containerName = containerName == null || containerName.isBlank() ? "event-alert-worker" : containerName;
        subnets = subnets == null ? List.of() : List.copyOf(subnets);
        command = command == null ? List.of() : List.copyOf(command);
    }

    public boolean enabled() {
        return !"off".equals(mode);
    }
}
