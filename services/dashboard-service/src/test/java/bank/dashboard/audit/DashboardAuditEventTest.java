package bank.dashboard.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The serialised event must match the auditor service's {@code ValidEvent}
 * envelope exactly. That conformance is the whole value of emitting now:
 * a renamed field here would silently turn into a rejected event there.
 */
class DashboardAuditEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serialisesToTheAuditorsEnvelopeFieldNames() throws Exception {
        JsonNode json = objectMapper.valueToTree(sampleEvent());

        assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("event_id", "event_type", "action", "source_service", "actor_id", "payload");
        assertThat(json.get("event_type").asText()).isEqualTo("DASHBOARD_VIEW");
        assertThat(json.get("source_service").asText()).isEqualTo("DASHBOARD");
    }

    @Test
    void serialisesThePayloadToSnakeCaseFieldNames() throws Exception {
        JsonNode payload = objectMapper.valueToTree(sampleEvent()).get("payload");

        assertThat(payload.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("scope", "window_from", "window_to", "panels", "result_count", "cache_hit", "datetime");
    }

    @Test
    void carriesNoClientIdentityOrContactDetail() {
        // An audit payload is where PII leaks in if nobody decides otherwise.
        // This asserts the decision: counts, scope and window, nothing else.
        String json = objectMapper.valueToTree(sampleEvent()).toString().toLowerCase();

        assertThat(json)
                .doesNotContain("email")
                .doesNotContain("firstname")
                .doesNotContain("lastname")
                .doesNotContain("phone")
                .doesNotContain("idp_sub");
    }

    @Test
    void identifiesTheActorByJwtSubjectOnly() {
        // Never the email: the subject identifies the actor without storing a
        // contact detail in the audit trail.
        DashboardAuditEvent event = sampleEvent();

        assertThat(event.actorId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(event.actorId()).doesNotContain("@");
    }

    @Test
    void namesTheActionPerEndpoint() {
        // "Someone used the dashboard" is not an audit trail. Each endpoint
        // gets its own action so a later reader knows what was looked at.
        assertThat(DashboardAction.values()).hasSize(5);
        assertThat(DashboardAuditEvent.of(DashboardAction.VIEW_AGENT_PERFORMANCE, "sub", payload()).action())
                .isEqualTo("VIEW_AGENT_PERFORMANCE");
    }

    @Test
    void givesEveryViewItsOwnEventId() {
        // The auditor stores a redelivered event once, by event_id, so two
        // views sharing an ID would lose one of them.
        assertThat(sampleEvent().eventId()).isNotBlank().isNotEqualTo(sampleEvent().eventId());
    }

    private DashboardAuditEvent sampleEvent() {
        return DashboardAuditEvent.of(
                DashboardAction.VIEW_OVERVIEW,
                "11111111-1111-1111-1111-111111111111",
                payload());
    }

    private DashboardViewPayload payload() {
        Map<String, String> panels = new LinkedHashMap<>();
        panels.put("clients", "UNAVAILABLE");
        panels.put("accounts", "OK");
        return new DashboardViewPayload("AGENT", "2026-08-22", "2026-09-20", panels, 1, false, "2026-09-20T04:10:00Z");
    }
}
