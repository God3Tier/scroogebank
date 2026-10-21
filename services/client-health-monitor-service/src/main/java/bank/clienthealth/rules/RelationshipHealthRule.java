package bank.clienthealth.rules;

import bank.clienthealth.activity.application.ActivityBaselineService.BaselineResult;
import bank.clienthealth.activity.application.ActivityBaselineService.ClientBaseline;
import java.util.List;

interface RelationshipHealthRule {
    List<DetectedSignal> evaluate(BaselineResult baseline, ClientBaseline client);
}
