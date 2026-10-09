package bank.user.activity;

import bank.audit.grpc.v1.AuditServiceGrpc;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Channel to the auditor's internal gRPC port (ADR-0021). The auditor is
 * reachable only inside the VPC, through ECS Service Connect, never through
 * the API gateway. The channel connects on first use, not at startup.
 */
@Configuration
public class ActivityConfig {

    /** Auditor failures the circuit breaker counts; caller errors do not trip it. */
    private static final Set<Status.Code> AUDITOR_FAILURES = Set.of(
            Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.INTERNAL,
            Status.Code.UNKNOWN, Status.Code.RESOURCE_EXHAUSTED);

    /**
     * TLS when a CA file is configured, as the target design requires;
     * plaintext otherwise, for local development only.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel auditorChannel(@Value("${auditor.grpc-target}") String target,
                                         @Value("${auditor.grpc-tls-ca:}") String tlsCa) throws IOException {
        ChannelCredentials credentials = tlsCa.isBlank()
                ? InsecureChannelCredentials.create()
                : TlsChannelCredentials.newBuilder().trustManager(new File(tlsCa)).build();
        return Grpc.newChannelBuilder(target, credentials).build();
    }

    @Bean
    public AuditServiceGrpc.AuditServiceBlockingStub auditServiceStub(ManagedChannel auditorChannel) {
        return AuditServiceGrpc.newBlockingStub(auditorChannel);
    }

    /**
     * Opens after half of the last 10 calls (at least 5) failed on the
     * auditor's side, then fails fast for 30 seconds before letting 2 trial
     * calls through. While open, the feed answers 503 at once instead of
     * holding request threads on a down auditor.
     */
    @Bean
    public CircuitBreaker auditorCircuitBreaker() {
        return CircuitBreaker.of("auditor", circuitBreakerConfig());
    }

    static CircuitBreakerConfig circuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordException(ActivityConfig::isAuditorFailure)
                .build();
    }

    static boolean isAuditorFailure(Throwable error) {
        return error instanceof StatusRuntimeException e && AUDITOR_FAILURES.contains(e.getStatus().getCode());
    }
}
