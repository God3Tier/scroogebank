package bank.user.activity;

import bank.audit.grpc.v1.AuditServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Channel to the auditor's internal gRPC port. The auditor is reachable only
 * inside the VPC (ECS Service Connect), never through the API gateway, so the
 * channel is plaintext. The channel connects on first use, not at startup.
 */
@Configuration
public class ActivityConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel auditorChannel(@Value("${auditor.grpc-target}") String target) {
        return ManagedChannelBuilder.forTarget(target).usePlaintext().build();
    }

    @Bean
    public AuditServiceGrpc.AuditServiceBlockingStub auditServiceStub(ManagedChannel auditorChannel) {
        return AuditServiceGrpc.newBlockingStub(auditorChannel);
    }
}
