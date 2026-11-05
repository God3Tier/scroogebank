package bank.alert.research;

import bank.alert.config.SearchOnAddProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AssignPublicIp;
import software.amazon.awssdk.services.ecs.model.Failure;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchOnAddTriggersTest {

    private static final SearchOnAddTrigger.ResearchRequest REQUEST = new SearchOnAddTrigger.ResearchRequest(
            List.of("SGX-D05", "SGX-O39"), "businesstimes.com.sg", Duration.ofDays(14), "corr-1");
    private static final SearchOnAddTrigger.ResearchRequest ALL_WEBSITES = new SearchOnAddTrigger.ResearchRequest(
            List.of("SGX-D05"), null, Duration.ofDays(14), "corr-2");

    private static SearchOnAddProperties properties(List<String> command) {
        return new SearchOnAddProperties("ecs", null, 0, null, "crm-sg-prod", "crm-sg-prod-event-alert-worker",
                null, List.of("subnet-a", "subnet-b"), "sg-alerts", command);
    }

    @Test
    void theWorkerReadsTheCompaniesTheDomainTheLookbackAndTheCorrelationId() {
        assertThat(SearchOnAddTrigger.workerEnvironment(REQUEST)).containsExactly(
                java.util.Map.entry("ALERT_WORKER_RESEARCH_INSTRUMENTS", "SGX-D05,SGX-O39"),
                java.util.Map.entry("ALERT_WORKER_RESEARCH_DOMAIN", "businesstimes.com.sg"),
                java.util.Map.entry("ALERT_WORKER_RESEARCH_LOOKBACK", "PT336H"),
                java.util.Map.entry("ALERT_WORKER_RESEARCH_CORRELATION_ID", "corr-1"));
    }

    @Test
    void withoutADomainTheWorkerIsNotToldOneSoItSearchesEveryWebsite() {
        assertThat(SearchOnAddTrigger.workerEnvironment(ALL_WEBSITES).keySet()).containsExactly(
                "ALERT_WORKER_RESEARCH_INSTRUMENTS", "ALERT_WORKER_RESEARCH_LOOKBACK", "ALERT_WORKER_RESEARCH_CORRELATION_ID");
    }

    @Test
    void ecsStartsTheWorkerTaskInThePrivateSubnetsWithTheResearchVariables() {
        EcsClient ecs = mock(EcsClient.class);
        when(ecs.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder().build());

        new EcsSearchOnAddTrigger(ecs, properties(List.of())).start(REQUEST);

        ArgumentCaptor<RunTaskRequest> sent = ArgumentCaptor.forClass(RunTaskRequest.class);
        verify(ecs).runTask(sent.capture());
        RunTaskRequest task = sent.getValue();
        assertThat(task.cluster()).isEqualTo("crm-sg-prod");
        assertThat(task.taskDefinition()).isEqualTo("crm-sg-prod-event-alert-worker");
        assertThat(task.count()).isEqualTo(1);
        assertThat(task.networkConfiguration().awsvpcConfiguration().subnets()).containsExactly("subnet-a", "subnet-b");
        assertThat(task.networkConfiguration().awsvpcConfiguration().securityGroups()).containsExactly("sg-alerts");
        assertThat(task.networkConfiguration().awsvpcConfiguration().assignPublicIp()).isEqualTo(AssignPublicIp.DISABLED);
        assertThat(task.overrides().containerOverrides()).hasSize(1);
        assertThat(task.overrides().containerOverrides().get(0).name()).isEqualTo("event-alert-worker");
        assertThat(task.overrides().containerOverrides().get(0).environment()).extracting(KeyValuePair::name)
                .containsExactly("ALERT_WORKER_RESEARCH_INSTRUMENTS", "ALERT_WORKER_RESEARCH_DOMAIN",
                        "ALERT_WORKER_RESEARCH_LOOKBACK", "ALERT_WORKER_RESEARCH_CORRELATION_ID");
    }

    @Test
    void ecsReportsATaskThatCouldNotBeStarted() {
        EcsClient ecs = mock(EcsClient.class);
        when(ecs.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .failures(Failure.builder().reason("RESOURCE:CPU").build()).build());

        assertThatThrownBy(() -> new EcsSearchOnAddTrigger(ecs, properties(List.of())).start(REQUEST))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESOURCE:CPU");
    }

    @Test
    void localProcessNeedsACommand() {
        assertThatThrownBy(() -> new LocalProcessSearchOnAddTrigger(properties(List.of())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void localProcessStartsTheConfiguredCommandWithoutWaitingForIt() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        SearchOnAddTrigger trigger = new LocalProcessSearchOnAddTrigger(properties(List.of(java, "-version")));

        trigger.start(REQUEST);
    }

    @Test
    void localProcessReportsACommandThatCannotStart() {
        SearchOnAddTrigger trigger = new LocalProcessSearchOnAddTrigger(properties(List.of("this-command-does-not-exist-anywhere")));

        assertThatThrownBy(() -> trigger.start(REQUEST)).isInstanceOf(IllegalStateException.class);
    }
}
