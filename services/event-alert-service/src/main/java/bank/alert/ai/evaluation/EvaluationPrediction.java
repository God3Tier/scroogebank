package bank.alert.ai.evaluation;

import bank.alert.ai.EventType;

public record EvaluationPrediction(String exampleId, EventType predictedType, boolean accepted) { }
