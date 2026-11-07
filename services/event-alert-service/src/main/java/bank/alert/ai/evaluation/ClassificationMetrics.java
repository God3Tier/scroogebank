package bank.alert.ai.evaluation;

public record ClassificationMetrics(int examples, int truePositives, int falsePositives,
        int falseNegatives, int abstentions, double precision, double recall,
        double f1, double abstentionRate) { }
