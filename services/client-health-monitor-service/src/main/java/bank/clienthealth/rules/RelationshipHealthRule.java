package bank.clienthealth.rules;

import bank.clienthealth.activity.application.ActivityBaselineService.BaselineResult;
import bank.clienthealth.activity.application.ActivityBaselineService.ClientBaseline;
import java.util.List;

interface RelationshipHealthRule {
    Evaluation evaluate(BaselineResult baseline, ClientBaseline client);

    record Evaluation(List<DetectedSignal> signals, boolean sufficientInput) {
        public Evaluation { signals = List.copyOf(signals); }
    }
}
