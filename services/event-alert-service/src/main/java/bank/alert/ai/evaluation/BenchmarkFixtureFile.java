package bank.alert.ai.evaluation;

import bank.alert.ai.EventType;

import java.util.List;

public record BenchmarkFixtureFile(String version, String dataClassification, String labelStatus,
        List<FixtureExample> examples) {
    public BenchmarkFixtureFile {
        if (version == null || version.isBlank() || !"synthetic-public-only".equals(dataClassification)
                || labelStatus == null || labelStatus.isBlank() || examples == null) {
            throw new IllegalArgumentException(
                    "fixture version, synthetic-public-only classification, labelStatus, and examples are required");
        }
        examples = List.copyOf(examples);
    }

    public List<EvaluationExample> evaluationExamples() {
        return examples.stream().map(example -> new EvaluationExample(
                example.id(), example.expectedEventType(), example.shouldClassify())).toList();
    }

    public record FixtureExample(String id, String title, String excerpt,
            List<String> candidateInstrumentIds, EventType expectedEventType,
            boolean shouldClassify, String expectedSafetyOutcome) {
        public FixtureExample {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("fixture example ID is required");
        }
    }
}
