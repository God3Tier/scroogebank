package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * When an agent adds a website or presses Search now, the API asks for one targeted search of the
 * companies concerned over the last {@code lookback} (ADR-0081).
 *
 * {@code mode} is {@code off} (the default), {@code ecs} (start the worker task in AWS) or
 * {@code local-process} (start the worker as a child process, for a developer's machine only).
 * {@code dailyCap} is the most company searches that may start in 24 hours; zero, the default, means no cap.
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
        dailyCap = Math.max(0, dailyCap);
        lookback = lookback == null ? Duration.ofDays(14) : lookback;
        containerName = containerName == null || containerName.isBlank() ? "event-alert-worker" : containerName;
        subnets = subnets == null ? List.of() : List.copyOf(subnets);
        command = command == null ? List.of() : List.copyOf(command);
    }

    public boolean enabled() {
        return !"off".equals(mode);
    }
}
