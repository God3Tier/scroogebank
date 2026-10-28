package bank.clienthealth.runtime;

import bank.clienthealth.activity.application.ActivityBaselineService;
import bank.clienthealth.activity.application.ActivityProjectionService;
import bank.clienthealth.analysis.application.RelationshipHealthAnalysisOrchestrator;
import bank.clienthealth.analysis.port.AnalysisRunPort;
import bank.clienthealth.contract.RelationshipTaskPort;
import bank.clienthealth.metrics.ClientHealthMetrics;
import bank.clienthealth.rules.RelationshipHealthRuleEngine;
import bank.clienthealth.rules.RelationshipHealthRuleset;
import bank.clienthealth.transaction.application.TransactionReplayService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClientHealthRuntimeProperties.class)
public class ClientHealthRuntimeConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "client-health.runtime", name = "mode", havingValue = "ANALYSIS")
    RelationshipHealthRuleEngine relationshipHealthRuleEngine(ClientHealthMetrics metrics) {
        return new RelationshipHealthRuleEngine(RelationshipHealthRuleset.v1(), metrics);
    }

    @Bean
    @ConditionalOnProperty(prefix = "client-health.runtime", name = "mode", havingValue = "ANALYSIS")
    RelationshipHealthAnalysisOrchestrator relationshipHealthAnalysisOrchestrator(
            TransactionReplayService replay,
            ActivityProjectionService projection,
            ActivityBaselineService baseline,
            RelationshipHealthRuleEngine rules,
            RelationshipTaskPort tasks,
            AnalysisRunPort runs,
            Clock clock,
            ClientHealthRuntimeProperties properties,
            ClientHealthMetrics metrics
    ) {
        return new RelationshipHealthAnalysisOrchestrator(
                replay, projection, baseline, rules, tasks, runs, clock,
                properties.leaseDuration(), metrics);
    }

    @Bean
    @ConditionalOnProperty(prefix = "client-health.runtime", name = "mode", havingValue = "ANALYSIS")
    AnalysisRunner analysisRunner(RelationshipHealthAnalysisOrchestrator orchestrator, Clock clock) {
        return new AnalysisRunner(orchestrator, clock);
    }
}
