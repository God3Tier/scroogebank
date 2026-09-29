package bank.alert.ai.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

public final class AiBenchmarkReportCli {
    private final ObjectMapper mapper;
    private final AiBenchmarkEvaluator evaluator;

    public AiBenchmarkReportCli(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.evaluator = new AiBenchmarkEvaluator();
    }

    public VersionedBenchmarkReport generate(Path fixturePath, Path runPath) throws IOException {
        BenchmarkFixtureFile fixture = mapper.readValue(fixturePath.toFile(), BenchmarkFixtureFile.class);
        BenchmarkRunFile run = mapper.readValue(runPath.toFile(), BenchmarkRunFile.class);
        BenchmarkReport report = evaluator.evaluate(fixture.evaluationExamples(), run.observations(),
                BenchmarkGates.candidateDefaults());
        boolean eligible = report.passed() && "HUMAN_REVIEWED".equals(fixture.labelStatus());
        return new VersionedBenchmarkReport(fixture.version(), run.model(), run.promptVersion(),
                fixture.labelStatus(), run.estimatedCostUsd(), eligible, report);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: AiBenchmarkReportCli <fixture.json> <run.json>");
        }
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        VersionedBenchmarkReport report = new AiBenchmarkReportCli(mapper)
                .generate(Path.of(args[0]), Path.of(args[1]));
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
