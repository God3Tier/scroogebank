package bank.alert.research;

import bank.alert.config.SearchOnAddProperties;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AssignPublicIp;
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration;
import software.amazon.awssdk.services.ecs.model.ContainerOverride;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.TaskOverride;

import java.util.List;

/**
 * Starts the worker's task definition once, with the research variables set (ADR-0080). It is the
 * same task, image, roles and subnets as the scheduled run, started by the API's task role.
 */
public class EcsSearchOnAddTrigger implements SearchOnAddTrigger {

    private final EcsClient ecs;
    private final SearchOnAddProperties properties;

    public EcsSearchOnAddTrigger(EcsClient ecs, SearchOnAddProperties properties) {
        this.ecs = ecs;
        this.properties = properties;
    }

    @Override
    public void start(ResearchRequest request) {
        List<KeyValuePair> environment = SearchOnAddTrigger.workerEnvironment(request).entrySet().stream()
                .map(entry -> KeyValuePair.builder().name(entry.getKey()).value(entry.getValue()).build())
                .toList();
        RunTaskResponse response = ecs.runTask(RunTaskRequest.builder()
                .cluster(properties.cluster())
                .taskDefinition(properties.taskDefinition())
                .launchType(LaunchType.FARGATE)
                .count(1)
                .networkConfiguration(NetworkConfiguration.builder()
                        .awsvpcConfiguration(AwsVpcConfiguration.builder()
                                .subnets(properties.subnets())
                                .securityGroups(properties.securityGroup())
                                .assignPublicIp(AssignPublicIp.DISABLED)
                                .build())
                        .build())
                .overrides(TaskOverride.builder()
                        .containerOverrides(ContainerOverride.builder()
                                .name(properties.containerName())
                                .environment(environment)
                                .build())
                        .build())
                .build());
        if (response.hasFailures() && !response.failures().isEmpty()) {
            throw new IllegalStateException("the worker task could not be started: "
                    + response.failures().get(0).reason());
        }
    }
}
