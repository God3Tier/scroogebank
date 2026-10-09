package bank.user.activity;

import bank.audit.grpc.v1.Activity;
import bank.audit.grpc.v1.AuditServiceGrpc;
import bank.audit.grpc.v1.ListActivitiesRequest;
import bank.audit.grpc.v1.ListActivitiesResponse;
import bank.user.exception.AuditorUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
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

    private final AtomicReference<ListActivitiesRequest> received = new AtomicReference<>();
    private final AtomicReference<ListActivitiesResponse> reply = new AtomicReference<>();
    private final AtomicReference<Status> failure = new AtomicReference<>();
    private final AtomicReference<Metadata> receivedHeaders = new AtomicReference<>();

    private Server server;
    private ManagedChannel channel;
    private ActivityService service;

    @BeforeEach
    void startFakeAuditor() throws IOException {
        String name = InProcessServerBuilder.generateName();
        ServerInterceptor captureHeaders = new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(
                    ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
                receivedHeaders.set(headers);
                return next.startCall(call, headers);
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(new AuditServiceGrpc.AuditServiceImplBase() {
                    @Override
                    public void listActivities(ListActivitiesRequest request, StreamObserver<ListActivitiesResponse> observer) {
                        received.set(request);
                        if (failure.get() != null) {
                            observer.onError(failure.get().asRuntimeException());
                            return;
                        }
                        observer.onNext(reply.get());
                        observer.onCompleted();
                    }
                }, captureHeaders))
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
        reply.set(ListActivitiesResponse.newBuilder()
                .addActivities(Activity.newBuilder()
                        .setId("entry-8").setSourceService("CRM")
                        .setAction("UPDATE").setActorId(agent.toString())
                        .setTimestamp(Timestamp.newBuilder().setSeconds(1_789_000_000L).setNanos(5))
                        .setPayload("{\"client_id\":\"c-1\"}"))
                .setNextPageToken("8")
                .build());

        ActivityPage page = service.list(new ActivityQuery(agent, from, to, 1, "9"), "caller-token");

        assertThat(receivedHeaders.get().get(ActivityService.AUTHORIZATION))
                .as("the caller's token is forwarded for the auditor to check")
                .isEqualTo("Bearer caller-token");

        ListActivitiesRequest request = received.get();
        assertThat(request.getActorId()).isEqualTo(agent.toString());
        assertThat(request.getFrom().getSeconds()).isEqualTo(from.getEpochSecond());
        assertThat(request.getTo().getSeconds()).isEqualTo(to.getEpochSecond());
        assertThat(request.getPageSize()).isEqualTo(1);
        assertThat(request.getPageToken()).isEqualTo("9");

        assertThat(page.nextCursor()).isEqualTo("8");
        ActivityResponse entry = page.content().getFirst();
        assertThat(entry.id()).isEqualTo("entry-8");
        assertThat(entry.sourceService()).isEqualTo("CRM");
        assertThat(entry.action()).isEqualTo("UPDATE");
        assertThat(entry.actorId()).isEqualTo(agent.toString());
        assertThat(entry.timestamp()).isEqualTo(Instant.ofEpochSecond(1_789_000_000L, 5));
        assertThat(entry.payload().get("client_id").asText()).isEqualTo("c-1");
    }

    @Test
    void omitsUnsetFiltersAndEndsOnLastPage() {
        reply.set(ListActivitiesResponse.newBuilder().build());

        ActivityPage page = service.list(new ActivityQuery(null, null, null, 20, null), "caller-token");

        ListActivitiesRequest request = received.get();
        assertThat(request.getActorId()).isEmpty();
        assertThat(request.hasFrom()).isFalse();
        assertThat(request.hasTo()).isFalse();
        assertThat(request.getPageToken()).isEmpty();
        assertThat(page.content()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void keepsNonJsonPayloadAsText() {
        reply.set(ListActivitiesResponse.newBuilder()
                .addActivities(Activity.newBuilder().setId("e").setPayload("not json"))
                .build());

        ActivityResponse entry = service.list(new ActivityQuery(null, null, null, 20, null), "caller-token").content().getFirst();

        assertThat(entry.payload().asText()).isEqualTo("not json");
    }

    @Test
    void unreachableAuditorIsReportedAsUnavailable() {
        failure.set(Status.UNAVAILABLE);

        assertThatThrownBy(() -> service.list(new ActivityQuery(null, null, null, 20, null), "caller-token"))
                .isInstanceOf(AuditorUnavailableException.class)
                .hasMessage("Activity log is temporarily unavailable");
    }

    @Test
    void auditorErrorIsReportedAsFailedRequest() {
        failure.set(Status.INTERNAL);

        assertThatThrownBy(() -> service.list(new ActivityQuery(null, null, null, 20, null), "caller-token"))
                .isInstanceOf(AuditorUnavailableException.class)
                .hasMessage("Activity log request failed");
    }
}
