package bank.alert.service;

import bank.alert.api.DecisionRequest;
import bank.alert.crm.CurrentOwnership;
import bank.alert.api.FeedbackRequest;
import bank.alert.domain.Alert;
import bank.alert.domain.AlertDecision;
import bank.alert.domain.AlertFeedback;
import bank.alert.domain.AlertStatus;
import bank.alert.domain.AuditOutbox;
import bank.alert.repository.AlertDecisionRepository;
import bank.alert.repository.AlertFeedbackRepository;
import bank.alert.repository.AlertRepository;
import bank.alert.repository.AuditOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AlertMutationService {

    private static final List<String> BACKLOG_STATUSES = List.of("PENDING", "FAILED");

    private final AlertRepository alertRepository;
    private final AlertDecisionRepository decisionRepository;
    private final AlertFeedbackRepository feedbackRepository;
    private final AuditOutboxRepository outboxRepository;
    private final AlertStateMachine stateMachine;
    private final CurrentOwnership currentOwnership;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxPendingAuditEvents;

    public AlertMutationService(
            AlertRepository alertRepository,
            AlertDecisionRepository decisionRepository,
            AlertFeedbackRepository feedbackRepository,
            AuditOutboxRepository outboxRepository,
            AlertStateMachine stateMachine,
            CurrentOwnership currentOwnership,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${alert.audit.max-pending:10000}") int maxPendingAuditEvents) {
        this.alertRepository = alertRepository;
        this.decisionRepository = decisionRepository;
        this.feedbackRepository = feedbackRepository;
        this.outboxRepository = outboxRepository;
        this.stateMachine = stateMachine;
        this.currentOwnership = currentOwnership;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.maxPendingAuditEvents = maxPendingAuditEvents;
    }

    @Transactional
    public AlertDecision recordDecision(
            UUID alertId,
            String actorSubject,
            String idempotencyKey,
            UUID correlationId,
            DecisionRequest request) {
        if (!request.decision().accepted()) {
            throw new IllegalArgumentException("decision is no longer accepted");
        }
        AlertDecision replay = decisionRepository
                .findByActorSubjectAndIdempotencyKey(actorSubject, idempotencyKey)
                .orElse(null);
        if (replay != null) {
            if (!replay.getAlertId().equals(alertId)
                    || replay.getDecision() != request.decision()
                    || !Objects.equals(replay.getNote(), request.note())
                    || replay.getAlertVersion() - 1 != request.expectedVersion()) {
                throw new AlertMutationConflictException("idempotency key was used for another decision");
            }
            return replay;
        }

        ensureAuditCapacity();
        currentOwnership.refresh();
        Alert alert = ownedAlert(alertId, actorSubject);
        requireVersion(alert, request.expectedVersion());
        AlertStatus previousStatus = alert.getStatus();
        AlertStatus newStatus = stateMachine.transition(previousStatus, request.decision());
        alert.transitionTo(newStatus);
        alertRepository.saveAndFlush(alert);

        Instant now = clock.instant();
        AlertDecision decision = decisionRepository.saveAndFlush(new AlertDecision(
                UUID.randomUUID(), alert.getId(), actorSubject, request.decision(),
                previousStatus, newStatus, request.note(), idempotencyKey, now,
                alert.getVersion()));
        persistOutbox(
                "alert.decision-recorded",
                decisionPayload(decision, previousStatus, newStatus, correlationId),
                now);
        return decision;
    }

    @Transactional
    public AlertFeedback recordFeedback(
            UUID alertId,
            String actorSubject,
            String idempotencyKey,
            UUID correlationId,
            FeedbackRequest request) {
        AlertFeedback replay = feedbackRepository
                .findByActorSubjectAndIdempotencyKey(actorSubject, idempotencyKey)
                .orElse(null);
        if (replay != null) {
            if (!replay.getAlertId().equals(alertId)
                    || replay.getRating() != request.rating()
                    || replay.getReasonCode() != request.reasonCode()
                    || !Objects.equals(replay.getComment(), request.comment())
                    || replay.getAlertVersion() != request.expectedVersion()) {
                throw new AlertMutationConflictException("idempotency key was used for other feedback");
            }
            return replay;
        }

        ensureAuditCapacity();
        currentOwnership.refresh();
        Alert alert = ownedAlert(alertId, actorSubject);
        requireVersion(alert, request.expectedVersion());
        Instant now = clock.instant();
        AlertFeedback feedback = feedbackRepository.saveAndFlush(new AlertFeedback(
                UUID.randomUUID(), alert.getId(), actorSubject, request.rating(),
                request.reasonCode(), request.comment(), idempotencyKey, now,
                alert.getVersion()));
        persistOutbox("alert.feedback-recorded", feedbackPayload(feedback, correlationId), now);
        return feedback;
    }

    private Alert ownedAlert(UUID alertId, String actorSubject) {
        return alertRepository.findOwnedByCurrentAgent(alertId, actorSubject)
                .orElseThrow(AlertNotFoundException::new);
    }

    private static void requireVersion(Alert alert, long expectedVersion) {
        if (alert.getVersion() != expectedVersion) {
            throw new AlertMutationConflictException("expected alert version is stale");
        }
    }

    private void ensureAuditCapacity() {
        try {
            if (outboxRepository.countByStatusIn(BACKLOG_STATUSES) >= maxPendingAuditEvents) {
                throw new AuditUnavailableException("audit outbox is at capacity");
            }
        } catch (DataAccessException exception) {
            throw new AuditUnavailableException("audit outbox availability cannot be verified", exception);
        }
    }

    private void persistOutbox(String eventType, ObjectNode payload, Instant now) {
        try {
            outboxRepository.saveAndFlush(new AuditOutbox(
                    UUID.randomUUID(), eventType, 1, payload.toString(), now));
        } catch (DataAccessException exception) {
            throw new AuditUnavailableException("durable audit persistence is unavailable", exception);
        }
    }

    private ObjectNode decisionPayload(
            AlertDecision decision,
            AlertStatus previousStatus,
            AlertStatus newStatus,
            UUID correlationId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("decisionId", decision.getId().toString());
        payload.put("alertId", decision.getAlertId().toString());
        payload.put("actorSubject", decision.getActorSubject());
        payload.put("actorRole", "AGENT");
        payload.put("correlationId", correlationId.toString());
        payload.put("decision", decision.getDecision().name());
        payload.put("previousStatus", previousStatus.name());
        payload.put("newStatus", newStatus.name());
        payload.put("alertVersion", decision.getAlertVersion());
        payload.put("occurredAt", decision.getDecidedAt().toString());
        return payload;
    }

    private ObjectNode feedbackPayload(AlertFeedback feedback, UUID correlationId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("feedbackId", feedback.getId().toString());
        payload.put("alertId", feedback.getAlertId().toString());
        payload.put("actorSubject", feedback.getActorSubject());
        payload.put("actorRole", "AGENT");
        payload.put("correlationId", correlationId.toString());
        payload.put("rating", feedback.getRating().name());
        payload.put("alertVersion", feedback.getAlertVersion());
        payload.put("occurredAt", feedback.getRecordedAt().toString());
        return payload;
    }
}
