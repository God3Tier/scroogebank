package bank.user.activity;

import bank.audit.grpc.v1.Activity;
import bank.audit.grpc.v1.AuditServiceGrpc;
import bank.audit.grpc.v1.ListActivitiesRequest;
import bank.audit.grpc.v1.ListActivitiesResponse;
import bank.user.domain.Role;
import bank.user.domain.User;
import bank.user.exception.AuditorUnavailableException;
import bank.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Runs ActivityService against a fake auditor on an in-process gRPC channel. */
class ActivityServiceTest {

    private final AtomicReference<ListActivitiesRequest> received = new AtomicReference<>();
    private final AtomicReference<Metadata> receivedHeaders = new AtomicReference<>();
    private final AtomicReference<ListActivitiesResponse> reply =
            new AtomicReference<>(ListActivitiesResponse.getDefaultInstance());
    /** Failures to return, one per call, before the reply. */
    private final Deque<Status> failures = new ArrayDeque<>();
    private final AtomicInteger calls = new AtomicInteger();

    private final UserRepository users = mock(UserRepository.class);
    private Server server;
    private ManagedChannel channel;
    private CircuitBreaker breaker;
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
                        calls.incrementAndGet();
                        received.set(request);
                        Status failure = failures.poll();
                        if (failure != null) {
                            observer.onError(failure.asRuntimeException());
                            return;
                        }
                        observer.onNext(reply.get());
                        observer.onCompleted();
                    }
                }, captureHeaders))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        breaker = CircuitBreaker.of("auditor-test", ActivityConfig.circuitBreakerConfig());
        service = new ActivityService(AuditServiceGrpc.newBlockingStub(channel), breaker, users, new ObjectMapper());
        when(users.findByIdpSubIn(any())).thenReturn(List.of());
    }

    @AfterEach
    void stopFakeAuditor() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static ActivityQuery anyActivity() {
        return new ActivityQuery(null, null, null, null, 20, null);
    }

    @Test
    void sendsFiltersAndTheCallersTokenAndMapsEntries() {
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-30T00:00:00Z");
        reply.set(ListActivitiesResponse.newBuilder()
                .addActivities(Activity.newBuilder()
                        .setId("entry-8").setSourceService("CRM")
                        .setAction("UPDATE").setActorId("agent-sub")
                        .setTimestamp(Timestamp.newBuilder().setSeconds(1_789_000_000L).setNanos(5))
                        .setPayload("{\"client_id\":\"c-1\"}"))
                .setNextPageToken("8")
                .build());

        ActivityPage page = service.list(new ActivityQuery("agent-sub", "client-123", from, to, 1, "9"), "caller-token");

        assertThat(receivedHeaders.get().get(ActivityService.AUTHORIZATION))
                .as("the caller's token is forwarded for the auditor to check")
                .isEqualTo("Bearer caller-token");
        ListActivitiesRequest request = received.get();
        assertThat(request.getActorId()).isEqualTo("agent-sub");
        assertThat(request.getClientId()).isEqualTo("client-123");
        assertThat(request.getFrom().getSeconds()).isEqualTo(from.getEpochSecond());
        assertThat(request.getTo().getSeconds()).isEqualTo(to.getEpochSecond());
        assertThat(request.getPageSize()).isEqualTo(1);
        assertThat(request.getPageToken()).isEqualTo("9");

        assertThat(page.nextCursor()).isEqualTo("8");
        ActivityResponse entry = page.content().getFirst();
        assertThat(entry.id()).isEqualTo("entry-8");
        assertThat(entry.sourceService()).isEqualTo("CRM");
        assertThat(entry.action()).isEqualTo("UPDATE");
        assertThat(entry.actorId()).isEqualTo("agent-sub");
        assertThat(entry.timestamp()).isEqualTo(Instant.ofEpochSecond(1_789_000_000L, 5));
        assertThat(entry.payload().get("client_id").asText()).isEqualTo("c-1");
    }

    @Test
    void namesTheActingUsersItKnows() {
        reply.set(ListActivitiesResponse.newBuilder()
                .addActivities(Activity.newBuilder().setId("e1").setActorId("agent-sub").setPayload("{}"))
                .addActivities(Activity.newBuilder().setId("e2").setActorId("system:root-admin-initializer").setPayload("{}"))
                .addActivities(Activity.newBuilder().setId("e3").setActorId("agent-sub").setPayload("{}"))
                .build());
        when(users.findByIdpSubIn(List.of("agent-sub", "system:root-admin-initializer"))).thenReturn(List.of(
                new User(UUID.randomUUID(), "Alice", "Tan", "alice@scroogebank.com", Role.AGENT, "agent-sub")));

        List<ActivityResponse> entries = service.list(anyActivity(), "caller-token").content();

        assertThat(entries).extracting(ActivityResponse::actorName).containsExactly("Alice Tan", null, "Alice Tan");
    }

    @Test
    void omitsUnsetFiltersAndEndsOnTheLastPage() {
        ActivityPage page = service.list(anyActivity(), "caller-token");

        ListActivitiesRequest request = received.get();
        assertThat(request.getActorId()).isEmpty();
        assertThat(request.hasFrom()).isFalse();
        assertThat(request.hasTo()).isFalse();
        assertThat(request.getPageToken()).isEmpty();
        assertThat(page.content()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void keepsANonJsonPayloadAsText() {
        reply.set(ListActivitiesResponse.newBuilder()
                .addActivities(Activity.newBuilder().setId("e").setPayload("not json"))
                .build());

        ActivityResponse entry = service.list(anyActivity(), "caller-token").content().getFirst();

        assertThat(entry.payload().asText()).isEqualTo("not json");
    }

    @Test
    void retriesOnceWhenTheAuditorIsUnavailable() {
        failures.add(Status.UNAVAILABLE);

        service.list(anyActivity(), "caller-token");

        assertThat(calls).hasValue(2);
    }

    @Test
    void reportsAnAuditorThatStaysUnavailable() {
        failures.add(Status.UNAVAILABLE);
        failures.add(Status.UNAVAILABLE);

        assertThatThrownBy(() -> service.list(anyActivity(), "caller-token"))
                .isInstanceOf(AuditorUnavailableException.class)
                .hasMessage("Activity log is temporarily unavailable");
        assertThat(calls).as("one retry, not more").hasValue(2);
    }

    @Test
    void doesNotRetryOtherErrors() {
        failures.add(Status.INTERNAL);

        assertThatThrownBy(() -> service.list(anyActivity(), "caller-token"))
                .isInstanceOf(AuditorUnavailableException.class)
                .hasMessage("Activity log request failed");
        assertThat(calls).hasValue(1);
    }

    @Test
    void stopsCallingAnAuditorThatKeepsFailing() {
        // Five failed requests (each retried once) open the circuit.
        for (int i = 0; i < 10; i++) {
            failures.add(Status.UNAVAILABLE);
        }
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.list(anyActivity(), "caller-token"))
                    .isInstanceOf(AuditorUnavailableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsBefore = calls.get();

        assertThatThrownBy(() -> service.list(anyActivity(), "caller-token"))
                .isInstanceOf(AuditorUnavailableException.class)
                .hasMessage("Activity log is temporarily unavailable");
        assertThat(calls).as("an open circuit does not call the auditor").hasValue(callsBefore);
    }

    @Test
    void callerErrorsDoNotOpenTheCircuit() {
        for (int i = 0; i < 10; i++) {
            failures.add(Status.PERMISSION_DENIED);
            assertThatThrownBy(() -> service.list(anyActivity(), "caller-token"))
                    .isInstanceOf(AuditorUnavailableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void callsHaveATwoSecondDeadline() {
        assertThat(ActivityService.DEADLINE).isEqualTo(Duration.ofSeconds(2));
        CircuitBreakerConfig config = ActivityConfig.circuitBreakerConfig();
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
    }
}
