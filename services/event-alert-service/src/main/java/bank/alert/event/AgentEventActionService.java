package bank.alert.event;

import bank.alert.crm.CurrentOwnership;
import bank.alert.domain.Alert;
import bank.alert.domain.AlertDecision;
import bank.alert.domain.AlertStatus;
import bank.alert.domain.AuditOutbox;
import bank.alert.event.AgentEventModels.ActionMember;
import bank.alert.event.AgentEventModels.ActionRequest;
import bank.alert.event.AgentEventModels.ActionResult;
import bank.alert.event.AgentEventModels.MemberOutcome;
import bank.alert.event.AgentEventModels.MemberResult;
import bank.alert.repository.AlertDecisionRepository;
import bank.alert.repository.AlertRepository;
import bank.alert.repository.AuditOutboxRepository;
import bank.alert.service.AlertMutationConflictException;
import bank.alert.service.AlertNotFoundException;
import bank.alert.service.AlertStateMachine;
import bank.alert.service.AuditUnavailableException;
import bank.alert.service.InvalidAlertTransitionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies one "Plan client briefing" / Dismiss choice to an explicit set of an agent's
 * affected clients, as a single audited group operation with a recorded outcome for
 * every member. "Inform" records the agent's intent; it never contacts a client.
 *
 * <p>Each member is checked on its own: current ownership, expected version, and the
 * alert state machine. A member that fails is recorded and skipped, never applied,
 * and a client who became affected later is not covered by an earlier choice.
 */
@Service
public class AgentEventActionService {

    private static final List<String> BACKLOG_STATUSES = List.of("PENDING", "FAILED");

    private final AlertRepository alerts;
    private final AlertDecisionRepository decisions;
    private final AuditOutboxRepository outbox;
    private final AlertStateMachine stateMachine;
    private final CurrentOwnership ownership;
    private final AgentEventQueryService queries;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int maxPendingAuditEvents;

    public AgentEventActionService(AlertRepository alerts, AlertDecisionRepository decisions,
            AuditOutboxRepository outbox, AlertStateMachine stateMachine, CurrentOwnership ownership,
            AgentEventQueryService queries, JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            @Value("${alert.audit.max-pending:10000}") int maxPendingAuditEvents) {
        this.alerts = alerts;
        this.decisions = decisions;
        this.outbox = outbox;
        this.stateMachine = stateMachine;
        this.ownership = ownership;
        this.queries = queries;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.maxPendingAuditEvents = maxPendingAuditEvents;
    }

    @Transactional
    public ActionResult apply(UUID eventId, String actor, String idempotencyKey, UUID correlationId,
            ActionRequest request) {
        if (!request.decision().accepted()) {
            throw new IllegalArgumentException("decision is no longer accepted");
        }
        Set<UUID> distinct = new HashSet<>();
        for (ActionMember member : request.members()) {
            if (!distinct.add(member.alertId())) {
                throw new IllegalArgumentException("duplicate client in selection");
            }
        }
        String hash = hash(eventId, request);

        List<Map<String, Object>> prior = jdbc.queryForList("""
                SELECT id, request_hash, public_event_id FROM agent_event_action
                 WHERE actor_subject = ? AND idempotency_key = ?
                """, actor, idempotencyKey);
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst().get("request_hash"))) {
                throw new AlertMutationConflictException("idempotency key was used for another action");
            }
            return queries.loadAction((UUID) prior.getFirst().get("id"));
        }

        ensureAuditCapacity();
        ownership.refresh();
        Integer visible = jdbc.queryForObject("""
                SELECT COUNT(*) FROM alert a LEFT JOIN client_assignment ca ON ca.client_ref = a.client_ref
                 WHERE a.public_event_id = ? AND %s = ?
                """.formatted(CurrentOwnership.OWNER_EXPRESSION), Integer.class, eventId, actor);
        if (visible == null || visible == 0) throw new AlertNotFoundException();

        Instant now = clock.instant();
        UUID actionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_event_action (id, public_event_id, actor_subject, decision, note,
                    idempotency_key, request_hash, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, actionId, eventId, actor, request.decision().name(), request.note(), idempotencyKey,
                hash, Timestamp.from(now));

        List<MemberResult> results = new ArrayList<>();
        Map<MemberOutcome, Integer> tally = new EnumMap<>(MemberOutcome.class);
        for (ActionMember member : request.members()) {
            MemberResult result = applyMember(eventId, actor, actionId, member, request, now);
            results.add(result);
            tally.merge(result.outcome(), 1, Integer::sum);
            jdbc.update("""
                    INSERT INTO agent_event_action_member (action_id, alert_id, outcome, previous_status, new_status)
                    VALUES (?, ?, ?, ?, ?)
                    """, actionId, member.alertId(), result.outcome().name(),
                    result.previousStatus() == null ? null : result.previousStatus().name(),
                    result.newStatus() == null ? null : result.newStatus().name());
        }
        persistAudit(actionId, eventId, actor, correlationId, request, tally, now);
        results.sort(Comparator.comparing(MemberResult::alertId));
        return new ActionResult(actionId, eventId, request.decision(), now, results);
    }

    private MemberResult applyMember(UUID eventId, String actor, UUID actionId, ActionMember member,
            ActionRequest request, Instant now) {
        Alert alert = alerts.findOwnedByCurrentAgent(member.alertId(), actor).orElse(null);
        if (alert == null || !eventId.equals(alert.getPublicEventId())) {
            return new MemberResult(member.alertId(), MemberOutcome.NOT_AUTHORISED, null, null);
        }
        AlertStatus previous = alert.getStatus();
        if (alert.getVersion() != member.expectedVersion()) {
            return new MemberResult(member.alertId(), MemberOutcome.STALE_VERSION, previous, null);
        }
        AlertStatus next;
        try {
            next = stateMachine.transition(previous, request.decision());
        } catch (InvalidAlertTransitionException exception) {
            return new MemberResult(member.alertId(), MemberOutcome.INVALID_TRANSITION, previous, null);
        }
        if (next == previous) {
            // The client already has this status: nothing to change and nothing to record.
            return new MemberResult(member.alertId(), MemberOutcome.APPLIED, previous, next);
        }
        alert.transitionTo(next);
        alerts.saveAndFlush(alert);
        decisions.saveAndFlush(new AlertDecision(UUID.randomUUID(), alert.getId(), actor, request.decision(),
                previous, next, request.note(), "ev:" + actionId + ":" + alert.getId(), now,
                alert.getVersion()));
        return new MemberResult(member.alertId(), MemberOutcome.APPLIED, previous, next);
    }

    private void ensureAuditCapacity() {
        try {
            if (outbox.countByStatusIn(BACKLOG_STATUSES) >= maxPendingAuditEvents) {
                throw new AuditUnavailableException("audit outbox is at capacity");
            }
        } catch (DataAccessException exception) {
            throw new AuditUnavailableException("audit outbox availability cannot be verified", exception);
        }
    }

    private void persistAudit(UUID actionId, UUID eventId, String actor, UUID correlationId,
            ActionRequest request, Map<MemberOutcome, Integer> tally, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actionId", actionId);
        payload.put("publicEventId", eventId);
        payload.put("actorSubject", actor);
        payload.put("actorRole", "AGENT");
        payload.put("correlationId", correlationId);
        payload.put("decision", request.decision().name());
        payload.put("memberCount", request.members().size());
        payload.put("applied", tally.getOrDefault(MemberOutcome.APPLIED, 0));
        payload.put("rejected", request.members().size() - tally.getOrDefault(MemberOutcome.APPLIED, 0));
        payload.put("clientContacted", false);
        payload.put("occurredAt", now.toString());
        try {
            outbox.saveAndFlush(new AuditOutbox(UUID.randomUUID(), "alert.event-action-recorded", 1,
                    mapper.writeValueAsString(payload), now));
        } catch (DataAccessException | JsonProcessingException exception) {
            throw new AuditUnavailableException("durable audit persistence is unavailable", exception);
        }
    }

    private static String hash(UUID eventId, ActionRequest request) {
        StringBuilder canonical = new StringBuilder().append(eventId).append('|')
                .append(request.decision()).append('|').append(request.note() == null ? "" : request.note());
        request.members().stream().sorted(Comparator.comparing(ActionMember::alertId))
                .forEach(m -> canonical.append('|').append(m.alertId()).append(':').append(m.expectedVersion()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
