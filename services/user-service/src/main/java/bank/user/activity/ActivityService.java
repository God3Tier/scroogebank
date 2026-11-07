package bank.user.activity;

import bank.audit.grpc.v1.Activity;
import bank.audit.grpc.v1.AuditLogEntry;
import bank.audit.grpc.v1.AuditServiceGrpc;
import bank.audit.grpc.v1.ListAuditLogsRequest;
import bank.audit.grpc.v1.ListAuditLogsResponse;
import bank.audit.grpc.v1.ListActivitiesRequest;
import bank.audit.grpc.v1.ListActivitiesResponse;
import bank.user.domain.User;
import bank.user.exception.AuditorUnavailableException;
import bank.user.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.protobuf.Timestamp;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Reads the activity feed from the auditor over gRPC (ADR-0021). */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityService {

    /** Long enough for one indexed query; short enough that a hung auditor fails the request fast. */
    static final Duration DEADLINE = Duration.ofSeconds(2);

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final AuditServiceGrpc.AuditServiceBlockingStub auditor;
    private final CircuitBreaker auditorCircuitBreaker;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * @param accessToken the caller's own access token. It is forwarded, and the
     *                    auditor checks it and decides what the caller may see.
     */
    public ActivityPage list(ActivityQuery query, String accessToken) {
        ListActivitiesRequest request = toRequest(query);
        ListActivitiesResponse response;
        try {
            response = auditorCircuitBreaker.executeSupplier(() -> callWithOneRetry(request, accessToken));
        } catch (CallNotPermittedException e) {
            log.warn("Auditor circuit breaker is open; not calling ListActivities");
            throw new AuditorUnavailableException("Activity log is temporarily unavailable", e);
        } catch (StatusRuntimeException e) {
            // Requests are validated before they are sent, so any error here
            // is the auditor's or the network's, not the caller's.
            log.warn("Auditor ListActivities failed: {}", e.getStatus());
            Status.Code code = e.getStatus().getCode();
            boolean transientFailure = code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
            throw new AuditorUnavailableException(transientFailure
                    ? "Activity log is temporarily unavailable"
                    : "Activity log request failed", e);
        }

        Map<String, String> actorNames = actorNamesFor(
                response.getActivitiesList().stream().map(Activity::getActorId).toList());
        return new ActivityPage(
                response.getActivitiesList().stream().map(entry -> toResponse(entry, actorNames)).toList(),
                response.getNextPageToken().isEmpty() ? null : response.getNextPageToken());
    }

    /** Returns every audit event. The REST controller and auditor both require ADMIN. */
    public AuditLogPage listAuditLogs(int size, String cursor, String accessToken) {
        ListAuditLogsRequest.Builder request = ListAuditLogsRequest.newBuilder().setPageSize(size);
        if (cursor != null) {
            request.setPageToken(cursor);
        }

        ListAuditLogsResponse response;
        try {
            response = auditorCircuitBreaker.executeSupplier(
                    () -> callAuditLogsWithOneRetry(request.build(), accessToken));
        } catch (CallNotPermittedException e) {
            log.warn("Auditor circuit breaker is open; not calling ListAuditLogs");
            throw new AuditorUnavailableException("Audit log is temporarily unavailable", e);
        } catch (StatusRuntimeException e) {
            log.warn("Auditor ListAuditLogs failed: {}", e.getStatus());
            Status.Code code = e.getStatus().getCode();
            boolean transientFailure = code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
            throw new AuditorUnavailableException(transientFailure
                    ? "Audit log is temporarily unavailable"
                    : "Audit log request failed", e);
        }

        Map<String, String> actorNames = actorNamesFor(
                response.getEntriesList().stream().map(AuditLogEntry::getActorId).toList());
        return new AuditLogPage(
                response.getEntriesList().stream().map(entry -> new AuditLogResponse(
                        entry.getId(), entry.getEventType(), entry.getSourceService(), entry.getAction(),
                        entry.getActorId(), actorNames.get(entry.getActorId()),
                        Instant.ofEpochSecond(entry.getTimestamp().getSeconds(), entry.getTimestamp().getNanos()),
                        parsePayload(entry.getPayload()))).toList(),
                response.getNextPageToken().isEmpty() ? null : response.getNextPageToken());
    }

    private ListAuditLogsResponse callAuditLogsWithOneRetry(ListAuditLogsRequest request, String accessToken) {
        try {
            return callAuditLogs(request, accessToken);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != Status.Code.UNAVAILABLE) {
                throw e;
            }
            log.info("Auditor unavailable; retrying ListAuditLogs once");
            return callAuditLogs(request, accessToken);
        }
    }

    private ListAuditLogsResponse callAuditLogs(ListAuditLogsRequest request, String accessToken) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer " + accessToken);
        return auditor.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                .listAuditLogs(request);
    }

    /**
     * Retries once, and only on UNAVAILABLE: the call did not reach a server,
     * so trying again is safe, and Service Connect may route it to another
     * auditor task. Each attempt has its own deadline.
     */
    private ListActivitiesResponse callWithOneRetry(ListActivitiesRequest request, String accessToken) {
        try {
            return call(request, accessToken);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != Status.Code.UNAVAILABLE) {
                throw e;
            }
            log.info("Auditor unavailable; retrying ListActivities once");
            return call(request, accessToken);
        }
    }

    private ListActivitiesResponse call(ListActivitiesRequest request, String accessToken) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer " + accessToken);
        return auditor.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                .listActivities(request);
    }

    private static ListActivitiesRequest toRequest(ActivityQuery query) {
        ListActivitiesRequest.Builder request = ListActivitiesRequest.newBuilder()
                .setPageSize(query.size());
        if (query.agentId() != null) {
            request.setActorId(query.agentId());
        }
        if (query.clientId() != null) {
            request.setClientId(query.clientId());
        }
        if (query.from() != null) {
            request.setFrom(toTimestamp(query.from()));
        }
        if (query.to() != null) {
            request.setTo(toTimestamp(query.to()));
        }
        if (query.cursor() != null) {
            request.setPageToken(query.cursor());
        }
        return request.build();
    }

    /** The CRM users who acted on this page, by Cognito sub, looked up in one query. */
    private Map<String, String> actorNamesFor(List<String> ids) {
        List<String> actorIds = ids.stream().distinct().toList();
        if (actorIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findByIdpSubIn(actorIds).stream()
                .collect(Collectors.toMap(User::getIdpSub, user -> user.getFirstName() + " " + user.getLastName()));
    }

    private ActivityResponse toResponse(Activity entry, Map<String, String> actorNames) {
        return new ActivityResponse(
                entry.getId(),
                entry.getSourceService(),
                entry.getAction(),
                entry.getActorId(),
                actorNames.get(entry.getActorId()),
                Instant.ofEpochSecond(entry.getTimestamp().getSeconds(), entry.getTimestamp().getNanos()),
                parsePayload(entry.getPayload()));
    }

    /** Payloads are JSON; anything else is passed through as a string rather than dropped. */
    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(payload);
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }
}
