package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounds on the source-to-alert pipeline that a worker run starts after ingestion. */
@ConfigurationProperties(prefix = "alert.pipeline")
public record PipelineProperties(int maxSourcesPerRun) {

    public PipelineProperties {
        if (maxSourcesPerRun < 0) throw new IllegalArgumentException("alert.pipeline.max-sources-per-run cannot be negative");
        // Each source can cost two model calls, so the default stays small.
        maxSourcesPerRun = maxSourcesPerRun == 0 ? 50 : Math.min(maxSourcesPerRun, 200);
    }
}
