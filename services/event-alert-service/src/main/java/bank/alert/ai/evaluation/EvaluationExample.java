package bank.alert.ai.evaluation;

import bank.alert.ai.EventType;

public record EvaluationExample(String id, EventType expectedType, boolean shouldClassify) { }
