package bank.user.activity;

import bank.audit.grpc.AuditLogEntry;
import bank.audit.grpc.AuditServiceGrpc;
import bank.audit.grpc.ListAuditLogsRequest;
import bank.audit.grpc.ListAuditLogsResponse;
import bank.user.exception.AuditorUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs ActivityService against a fake auditor on an in-process gRPC channel. */
class ActivityServiceTest {

    private final AtomicReference<ListAuditLogsRequest> received = new AtomicReference<>();
    private final AtomicReference<ListAuditLogsResponse> reply = new AtomicReference<>();
    private final AtomicReference<Status> failure = new AtomicReference<>();

    private Server server;
    private ManagedChannel channel;
    private ActivityService service;

    @BeforeEach
    void startFakeAuditor() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new AuditServiceGrpc.AuditServiceImplBase() {
                    @Override
                    public void listAuditLogs(ListAuditLogsRequest request, StreamObserver<ListAuditLogsResponse> observer) {
                        received.set(request);
                        if (failure.get() != null) {
                            observer.onError(failure.get().asRuntimeException());
                            return;
                        }
                        observer.onNext(reply.get());
                        observer.onCompleted();
                    }
                })
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        service = new ActivityService(AuditServiceGrpc.newBlockingStub(channel), new ObjectMapper());
    }

    @AfterEach
    void stopFakeAuditor() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void sendsFiltersAndMapsEntries() {
        UUID agent = UUID.randomUUID();
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-30T00:00:00Z");
        reply.set(ListAuditLogsResponse.newBuilder()
                .addEntries(AuditLogEntry.newBuilder()
                        .setId("entry-8").setSequenceNo(8).setSourceService("CRM").setEventType("CRUD")
                        .setAction("UPDATE_CLIENT").setActorId(agent.toString())
                        .setTimestamp(Timestamp.newBuilder().setSeconds(1_789_000_000L).setNanos(5))
                        .setPayload("{\"client_id\":\"c-1\"}"))
                .setNextBeforeSequenceNo(8)
                .build());

        ActivityPage page = service.list(new ActivityQuery(agent, from, to, 1, 9L));

        ListAuditLogsRequest request = received.get();
        assertThat(request.getActorId()).isEqualTo(agent.toString());
        assertThat(request.getSourceService()).isEmpty();
        assertThat(request.getFrom().getSeconds()).isEqualTo(from.getEpochSecond());
        assertThat(request.getTo().getSeconds()).isEqualTo(to.getEpochSecond());
        assertThat(request.getPageSize()).isEqualTo(1);
        assertThat(request.getBeforeSequenceNo()).isEqualTo(9);

        assertThat(page.nextCursor()).isEqualTo(8L);
        ActivityResponse entry = page.content().getFirst();
        assertThat(entry.id()).isEqualTo("entry-8");
        assertThat(entry.sequenceNo()).isEqualTo(8);
        assertThat(entry.action()).isEqualTo("UPDATE_CLIENT");
        assertThat(entry.actorId()).isEqualTo(agent.toString());
        assertThat(entry.timestamp()).isEqualTo(Instant.ofEpochSecond(1_789_000_000L, 5));
        assertThat(entry.payload().get("client_id").asText()).isEqualTo("c-1");
    }

    @Test
    void omitsUnsetFiltersAndEndsOnLastPage() {
        reply.set(ListAuditLogsResponse.newBuilder().build());

        ActivityPage page = service.list(new ActivityQuery(null, null, null, 20, null));

        ListAuditLogsRequest request = received.get();
        assertThat(request.getActorId()).isEmpty();
        assertThat(request.hasFrom()).isFalse();
        assertThat(request.hasTo()).isFalse();
        assertThat(request.getBeforeSequenceNo()).isZero();
        assertThat(page.content()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void keepsNonJsonPayloadAsText() {
        reply.set(ListAuditLogsResponse.newBuilder()
                .addEntries(AuditLogEntry.newBuilder().setId("e").setPayload("not json"))
                .build());

        ActivityResponse entry = service.list(new ActivityQuery(null, null, null, 20, null)).content().getFirst();

        assertThat(entry.payload().asText()).isEqualTo("not json");
    }

    @Test
    void unreachableAuditorIsReportedAsUnavailable() {
        failure.set(Status.UNAVAILABLE);

        assertThatThrownBy(() -> service.list(new ActivityQuery(null, null, null, 20, null)))
                .isInstanceOf(AuditorUnavailableException.class)
                .hasMessage("Activity log is temporarily unavailable");
    }

    @Test
    void auditorErrorIsReportedAsFailedRequest() {
        failure.set(Status.INTERNAL);

        assertThatThrownBy(() -> service.list(new ActivityQuery(null, null, null, 20, null)))
                .isInstanceOf(AuditorUnavailableException.class)
                .hasMessage("Activity log request failed");
    }
}
