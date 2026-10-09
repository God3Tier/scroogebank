package bank.alert.ai.evaluation;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ClassificationBenchmark {
    public ClassificationMetrics measure(List<EvaluationExample> examples,
            List<EvaluationPrediction> predictions) {
        Map<String, EvaluationPrediction> byId = predictions.stream().collect(Collectors.toMap(
                EvaluationPrediction::exampleId, Function.identity(),
                (left, right) -> { throw new IllegalArgumentException("duplicate prediction: " + left.exampleId()); }));
        int tp = 0;
        int fp = 0;
        int fn = 0;
        int abstentions = 0;
        for (EvaluationExample example : examples) {
            EvaluationPrediction prediction = byId.get(example.id());
            if (prediction == null || !prediction.accepted()) {
                abstentions++;
                if (example.shouldClassify()) fn++;
                continue;
            }
            boolean correct = example.shouldClassify() && prediction.predictedType() == example.expectedType();
            if (correct) tp++;
            else {
                fp++;
                if (example.shouldClassify()) fn++;
            }
        }
        double precision = ratio(tp, tp + fp);
        double recall = ratio(tp, tp + fn);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        return new ClassificationMetrics(examples.size(), tp, fp, fn, abstentions,
                precision, recall, f1, ratio(abstentions, examples.size()));
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }
}
