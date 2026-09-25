package bank.user.activity;

import bank.audit.grpc.AuditLogEntry;
import bank.audit.grpc.AuditServiceGrpc;
import bank.audit.grpc.ListAuditLogsRequest;
import bank.audit.grpc.ListAuditLogsResponse;
import bank.user.exception.AuditorUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Reads the audit log from the auditor over gRPC for the admin activity feed. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityService {

    /** Long enough for one indexed query; short enough that a hung auditor fails the request fast. */
    static final long DEADLINE_SECONDS = 3;

    private final AuditServiceGrpc.AuditServiceBlockingStub auditor;
    private final ObjectMapper objectMapper;

    public ActivityPage list(ActivityQuery query) {
        ListAuditLogsRequest.Builder request = ListAuditLogsRequest.newBuilder()
                .setPageSize(query.size());
        if (query.agentId() != null) {
            request.setActorId(query.agentId().toString());
        }
        if (query.from() != null) {
            request.setFrom(toTimestamp(query.from()));
        }
        if (query.to() != null) {
            request.setTo(toTimestamp(query.to()));
        }
        if (query.cursor() != null) {
            request.setBeforeSequenceNo(query.cursor());
        }

        ListAuditLogsResponse response;
        try {
            response = auditor.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .listAuditLogs(request.build());
        } catch (StatusRuntimeException e) {
            // Requests are validated before they are sent, so any error here
            // is the auditor's or the network's, not the caller's.
            log.warn("Auditor ListAuditLogs failed: {}", e.getStatus());
            Status.Code code = e.getStatus().getCode();
            boolean transientFailure = code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
            throw new AuditorUnavailableException(transientFailure
                    ? "Activity log is temporarily unavailable"
                    : "Activity log request failed", e);
        }

        return new ActivityPage(
                response.getEntriesList().stream().map(this::toResponse).toList(),
                response.getNextBeforeSequenceNo() == 0 ? null : response.getNextBeforeSequenceNo());
    }

    private ActivityResponse toResponse(AuditLogEntry entry) {
        return new ActivityResponse(
                entry.getId(),
                entry.getSequenceNo(),
                entry.getSourceService(),
                entry.getEventType(),
                entry.getAction(),
                entry.getActorId(),
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
