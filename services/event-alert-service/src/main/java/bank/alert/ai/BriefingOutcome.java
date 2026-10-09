package bank.alert.ai;

public record BriefingOutcome(BriefingStatus status, AgentBriefing briefing,
        String reasonCode, int attempts) { }
