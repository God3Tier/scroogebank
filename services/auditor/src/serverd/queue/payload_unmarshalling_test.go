package queue

import (
	"auditor/src/internal/models"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

// buildEvent marshals a payload and wraps it into a ValidEvent, matching what
// the caller is expected to construct before invoking validatePayload.
func buildEvent(t *testing.T, eventType, action, sourceService string, payload any) ValidEvent {
	t.Helper()

	payloadBytes, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("failed to marshal test payload: %v", err)
	}

	return ValidEvent{
		EventType:     eventType,
		Action:        action,
		SourceService: sourceService,
		ActorID:       "test-actor",
		Payload:       payloadBytes,
	}
}

func TestValidatePayload_ValidCRUD_CRMService(t *testing.T) {
	for _, action := range []string{"CREATE", "GET", "UPDATE", "DELETE"} {
		t.Run(action, func(t *testing.T) {
			event := buildEvent(t, "CRUD", action, string(models.CRMServiceType), ValidCRUDPayload{
				AttributeName: "First Name|Address",
				BeforeValue:   "LEE|ABC",
				AfterValue:    "TAN|XX",
				AgentID:       "AGT-001",
				ClientID:      "CLI-001",
				DateTime:      time.Now().Format(time.RFC3339),
			})

			if err := validatePayload(event); err != nil {
				t.Errorf("expected CRM CRUD action %q to pass, got error: %v", action, err)
			}
		})
	}
}

func TestValidatePayload_CRMRejectsUnknownAction(t *testing.T) {
	event := buildEvent(t, "CRUD", "UPSERT", string(models.CRMServiceType), ValidCRUDPayload{})

	if err := validatePayload(event); err == nil {
		t.Error("expected CRM to reject an unsupported action, got nil")
	}
}

func TestValidatePayload_CRMRequiresCRUDEventType(t *testing.T) {
	event := buildEvent(t, "TRANSACTION_INGESTION", "CREATE", string(models.CRMServiceType), ValidCRUDPayload{})

	if err := validatePayload(event); err == nil {
		t.Error("expected CRM to reject a non-CRUD event_type, got nil")
	}
}

func TestValidatePayload_ValidCRUD_UserManagementService(t *testing.T) {
	event := buildEvent(t, "CRUD", "UPDATE", string(models.UserManagementServiceType), ValidCRUDPayload{
		AttributeName: "Role",
		BeforeValue:   "AGENT",
		AfterValue:    "ADMIN",
		AgentID:       "AGT-002",
		ClientID:      "",
		DateTime:      time.Now().Format(time.RFC3339),
	})

	if err := validatePayload(event); err != nil {
		t.Errorf("expected valid CRUD event from UserManagement service to pass, got error: %v", err)
	}
}

func TestValidatePayload_UserManagementRoleChange(t *testing.T) {
	event := buildEvent(t, "CRUD", "ROLE_CHANGE", string(models.UserManagementServiceType), ValidUserAuditPayload{
		ResourceType: "USER",
		ResourceID:   "8f0c6f1e-2b9a-4c11-9d3e-5a7b1c2d3e4f",
		Endpoint:     "/api/users/{id}/role",
		HTTPMethod:   "PATCH",
		StatusCode:   200,
		DateTime:     time.Now().Format(time.RFC3339Nano),
		UserRole:     "ADMIN",
	})

	if err := validatePayload(event); err != nil {
		t.Errorf("expected role change event from UserManagement service to pass, got error: %v", err)
	}
}

func TestValidatePayload_UserManagementLogout(t *testing.T) {
	event := buildEvent(t, "CRUD", "LOGOUT", string(models.UserManagementServiceType), ValidUserAuditPayload{
		ResourceType: "USER",
		ResourceID:   "8f0c6f1e-2b9a-4c11-9d3e-5a7b1c2d3e4f",
		Endpoint:     "/api/users/me/logout",
		HTTPMethod:   "POST",
		StatusCode:   204,
		DateTime:     time.Now().Format(time.RFC3339Nano),
		UserRole:     "AGENT",
	})

	if err := validatePayload(event); err != nil {
		t.Errorf("expected logout event from UserManagement service to pass, got error: %v", err)
	}
}

func userOperation(resourceType, resourceID, endpoint, method string, status int) ValidUserAuditPayload {
	return ValidUserAuditPayload{
		ResourceType: resourceType,
		ResourceID:   resourceID,
		Endpoint:     endpoint,
		HTTPMethod:   method,
		StatusCode:   status,
		DateTime:     time.Now().Format(time.RFC3339Nano),
	}
}

func TestValidatePayload_UserManagementOtherResources(t *testing.T) {
	const id = "123e4567-e89b-12d3-a456-426614174000"
	tests := []struct {
		name    string
		action  string
		payload ValidUserAuditPayload
	}{
		{"corrupted-log list read has no resource id", "READ",
			userOperation("CORRUPTED_AUDIT_LOG", "", "/api/corrupted-logs", "GET", 200)},
		{"corrupted-log create", "CREATE",
			userOperation("CORRUPTED_AUDIT_LOG", id, "/api/corrupted-logs", "POST", 201)},
		{"corrupted-log delete", "DELETE",
			userOperation("CORRUPTED_AUDIT_LOG", id, "/api/corrupted-logs/{id}", "DELETE", 200)},
		{"account transactions read", "READ",
			userOperation("ACCOUNT", id, "/api/accounts/{accountId}/transactions", "GET", 200)},
		{"transaction list read has no resource id", "READ",
			userOperation("TRANSACTION", "", "/api/transactions", "GET", 200)},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			event := buildEvent(t, "CRUD", tt.action, string(models.UserManagementServiceType), tt.payload)

			if err := validatePayload(event); err != nil {
				t.Errorf("expected %s on %s to pass, got error: %v", tt.action, tt.payload.ResourceType, err)
			}
		})
	}
}

func TestValidatePayload_UserManagementRejectsInvalidOperations(t *testing.T) {
	const id = "123e4567-e89b-12d3-a456-426614174000"
	tests := []struct {
		name    string
		action  string
		payload ValidUserAuditPayload
	}{
		{"unknown resource type", "READ",
			userOperation("CLIENT", id, "/api/clients/{id}", "GET", 200)},
		{"users cannot be deleted", "DELETE",
			userOperation("USER", id, "/api/users/{id}", "DELETE", 204)},
		{"corrupted logs have no role change", "ROLE_CHANGE",
			userOperation("CORRUPTED_AUDIT_LOG", id, "/api/corrupted-logs/{id}", "PATCH", 200)},
		{"successful corrupted-log create needs a resource id", "CREATE",
			userOperation("CORRUPTED_AUDIT_LOG", "", "/api/corrupted-logs", "POST", 201)},
		{"successful user read needs a resource id", "READ",
			userOperation("USER", "", "/api/users/{id}", "GET", 200)},
		{"transactions are only read", "DELETE",
			userOperation("TRANSACTION", id, "/api/transactions", "DELETE", 200)},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			event := buildEvent(t, "CRUD", tt.action, string(models.UserManagementServiceType), tt.payload)

			if err := validatePayload(event); err == nil {
				t.Errorf("expected %s on %s to be rejected, got nil", tt.action, tt.payload.ResourceType)
			}
		})
	}
}

func TestValidatePayload_UserManagementFailedCreateNeedsNoResourceID(t *testing.T) {
	event := buildEvent(t, "CRUD", "CREATE", string(models.UserManagementServiceType),
		userOperation("CORRUPTED_AUDIT_LOG", "", "/api/corrupted-logs", "POST", 400))

	if err := validatePayload(event); err != nil {
		t.Errorf("expected a failed create without a resource id to pass, got error: %v", err)
	}
}

func TestValidatePayload_ValidTransaction_FileCompleted(t *testing.T) {
	now := time.Now()
	event := buildEvent(t, "TRANSACTION_INGESTION", "FILE_PROCESSING_COMPLETED", string(models.TransactionServiceType), ValidFileCompletedPayload{
		FileName:         "transactions_2026_09_18.csv",
		ProcessingStart:  now,
		ProcessingEnd:    now.Add(5 * time.Second),
		Status:           "Partially Successful",
		RecordsProcessed: 100,
		RecordsAccepted:  95,
		RecordsRejected:  5,
		ParseFailures: []ParseFailure{
			{RecordRef: "row 12", Reason: "missing account_number"},
		},
	})

	if err := validatePayload(event); err != nil {
		t.Errorf("expected valid FILE_PROCESSING_COMPLETED event to pass, got error: %v", err)
	}
}

func TestValidatePayload_ValidTransaction_ProcessingErrors(t *testing.T) {
	tests := []struct {
		name   string
		action string
	}{
		{"file format invalid", "FILE_FORMAT_INVALID"},
		{"connection failure", "CONNECTION_FAILURE"},
		{"system exception", "SYSTEM_EXCEPTION"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			event := buildEvent(t, "TRANSACTION_INGESTION", tt.action, string(models.TransactionServiceType), ProcessingErrorPayload{
				FileName: "transactions_2026_09_18.csv",
				Stage:    tt.action,
				Error:    "simulated failure",
			})

			if err := validatePayload(event); err != nil {
				t.Errorf("expected valid %s event to pass, got error: %v", tt.action, err)
			}
		})
	}
}

func dashboardView() ValidDashboardViewPayload {
	return ValidDashboardViewPayload{
		Scope:       "AGENT",
		WindowFrom:  "2026-09-01",
		WindowTo:    "2026-09-30",
		Panels:      map[string]string{"clients": "OK", "transactions": "UNAVAILABLE"},
		ResultCount: 1,
		CacheHit:    true,
		DateTime:    time.Now().UTC().Format(time.RFC3339Nano),
	}
}

// transaction-service's records of transaction reads (ADR-0064).
func TestValidatePayload_TransactionReads(t *testing.T) {
	const id = "123e4567-e89b-12d3-a456-426614174000"
	tests := []struct {
		name    string
		payload ValidUserAuditPayload
	}{
		{"an account's transactions", userOperation("ACCOUNT", id, "/api/accounts/{accountId}/transactions", "GET", 200)},
		{"another agent's account, refused, needs no resource id", userOperation("ACCOUNT", "", "/api/accounts/{accountId}/transactions", "GET", 404)},
		{"the transaction list has no resource id", userOperation("TRANSACTION", "", "/api/transactions", "GET", 200)},
		{"an agent refused the transaction list", userOperation("TRANSACTION", "", "/api/transactions", "GET", 403)},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			event := buildEvent(t, "CRUD", "READ", string(models.TransactionServiceType), tt.payload)

			if err := validatePayload(event); err != nil {
				t.Errorf("expected the read to pass, got error: %v", err)
			}
		})
	}
}

func TestValidatePayload_TransactionReadsRejectInvalidEvents(t *testing.T) {
	const id = "123e4567-e89b-12d3-a456-426614174000"
	tests := []struct {
		name    string
		action  string
		actorID string
		payload any
	}{
		{"transactions are only read", "DELETE", "agent-sub",
			userOperation("TRANSACTION", id, "/api/transactions", "DELETE", 200)},
		{"an ingestion action is not a read", "FILE_PROCESSING_COMPLETED", "agent-sub",
			userOperation("TRANSACTION", "", "/api/transactions", "GET", 200)},
		{"a resource transaction-service does not serve", "READ", "agent-sub",
			userOperation("USER", id, "/api/users/{id}", "GET", 200)},
		{"a successful account read needs its account", "READ", "agent-sub",
			userOperation("ACCOUNT", "", "/api/accounts/{accountId}/transactions", "GET", 200)},
		{"a read needs an actor", "READ", "",
			userOperation("TRANSACTION", "", "/api/transactions", "GET", 200)},
		{"a read needs an endpoint", "READ", "agent-sub",
			userOperation("TRANSACTION", "", "", "GET", 200)},
		{"a read needs a real status", "READ", "agent-sub",
			userOperation("TRANSACTION", "", "/api/transactions", "GET", 0)},
		{"a read needs a time", "READ", "agent-sub",
			ValidUserAuditPayload{ResourceType: "TRANSACTION", Endpoint: "/api/transactions", HTTPMethod: "GET", StatusCode: 200}},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			event := buildEvent(t, "CRUD", tt.action, string(models.TransactionServiceType), tt.payload)
			event.ActorID = tt.actorID

			if err := validatePayload(event); err == nil {
				t.Errorf("expected an error, got nil")
			}
		})
	}
}

// A read is CRUD, so it cannot arrive as an ingestion event.
func TestValidatePayload_TransactionReadIsNotAnIngestionEvent(t *testing.T) {
	event := buildEvent(t, "TRANSACTION_INGESTION", "READ", string(models.TransactionServiceType),
		userOperation("TRANSACTION", "", "/api/transactions", "GET", 200))

	if err := validatePayload(event); err == nil {
		t.Error("expected an error, got nil")
	}
}

func TestValidatePayload_ValidDashboardView(t *testing.T) {
	for _, action := range []string{"VIEW_OVERVIEW", "VIEW_PORTFOLIO", "VIEW_TRANSACTIONS", "VIEW_ACTION_ITEMS"} {
		t.Run(action, func(t *testing.T) {
			event := buildEvent(t, "DASHBOARD_VIEW", action, string(models.DashboardServiceType), dashboardView())

			if err := validatePayload(event); err != nil {
				t.Errorf("expected dashboard %s to pass, got error: %v", action, err)
			}
		})
	}

	adminView := dashboardView()
	adminView.Scope = "ADMIN"
	event := buildEvent(t, "DASHBOARD_VIEW", "VIEW_AGENT_PERFORMANCE", string(models.DashboardServiceType), adminView)
	if err := validatePayload(event); err != nil {
		t.Errorf("expected an admin's per-agent view to pass, got error: %v", err)
	}
}

func TestValidatePayload_DashboardRejectsInvalidEvents(t *testing.T) {
	tests := []struct {
		name      string
		eventType string
		action    string
		actor     string
		change    func(*ValidDashboardViewPayload)
	}{
		{name: "wrong event type", eventType: "CRUD", action: "VIEW_OVERVIEW", actor: "a1"},
		{name: "unknown action", eventType: "DASHBOARD_VIEW", action: "EXPORT", actor: "a1"},
		{name: "missing actor", eventType: "DASHBOARD_VIEW", action: "VIEW_OVERVIEW", actor: " "},
		{name: "unknown scope", eventType: "DASHBOARD_VIEW", action: "VIEW_OVERVIEW", actor: "a1",
			change: func(p *ValidDashboardViewPayload) { p.Scope = "SERVICE" }},
		{name: "agent viewing the per-agent table", eventType: "DASHBOARD_VIEW", action: "VIEW_AGENT_PERFORMANCE", actor: "a1"},
		{name: "window not a date", eventType: "DASHBOARD_VIEW", action: "VIEW_OVERVIEW", actor: "a1",
			change: func(p *ValidDashboardViewPayload) { p.WindowFrom = "last month" }},
		{name: "window ends before it starts", eventType: "DASHBOARD_VIEW", action: "VIEW_OVERVIEW", actor: "a1",
			change: func(p *ValidDashboardViewPayload) { p.WindowFrom, p.WindowTo = p.WindowTo, p.WindowFrom }},
		{name: "no panels", eventType: "DASHBOARD_VIEW", action: "VIEW_OVERVIEW", actor: "a1",
			change: func(p *ValidDashboardViewPayload) { p.Panels, p.ResultCount = nil, 0 }},
		{name: "more results than panels", eventType: "DASHBOARD_VIEW", action: "VIEW_OVERVIEW", actor: "a1",
			change: func(p *ValidDashboardViewPayload) { p.ResultCount = 3 }},
		{name: "missing datetime", eventType: "DASHBOARD_VIEW", action: "VIEW_OVERVIEW", actor: "a1",
			change: func(p *ValidDashboardViewPayload) { p.DateTime = "" }},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			payload := dashboardView()
			if tt.change != nil {
				tt.change(&payload)
			}
			event := buildEvent(t, tt.eventType, tt.action, string(models.DashboardServiceType), payload)
			event.ActorID = tt.actor

			if err := validatePayload(event); err == nil {
				t.Errorf("expected %s to be rejected, got nil", tt.name)
			}
		})
	}
}

// cognitoLogin is a message exactly as services/cognito-triggers sends it for
// its sample login event (src/test/resources/events/post-authentication.json;
// AuditMessageTest checks the same text).
const cognitoLogin = `{"event_id":"evt-1","event_type":"AUTHENTICATION","action":"LOGIN",` +
	`"source_service":"COGNITO_TRIGGERS","actor_id":"4a1b2c3d-0000-4000-8000-000000000001",` +
	`"payload":{"user_pool_id":"ap-southeast-1_Example01","trigger_source":"PostAuthentication_Authentication",` +
	`"datetime":"2026-10-13T05:54:00.123Z","app_client_id":"example0app0client0id0001"}}`

func authenticationPayload(triggerSource string) ValidAuthenticationPayload {
	return ValidAuthenticationPayload{
		UserPoolID:    "ap-southeast-1_Example01",
		AppClientID:   "example0app0client0id0001",
		TriggerSource: triggerSource,
		DateTime:      "2026-10-13T05:54:00.123Z",
	}
}

func TestValidatePayload_CognitoTriggersMessage(t *testing.T) {
	var event ValidEvent
	if err := json.Unmarshal([]byte(cognitoLogin), &event); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if err := validatePayload(event); err != nil {
		t.Errorf("expected the triggers' login message to pass, got error: %v", err)
	}
}

func TestValidatePayload_ValidAuthentication(t *testing.T) {
	tests := []struct {
		action        string
		triggerSource string
	}{
		{action: "LOGIN", triggerSource: "PostAuthentication_Authentication"},
		{action: "PASSWORD_RESET", triggerSource: "PostConfirmation_ConfirmForgotPassword"},
	}

	for _, tt := range tests {
		t.Run(tt.action, func(t *testing.T) {
			event := buildEvent(t, "AUTHENTICATION", tt.action, string(models.CognitoTriggersServiceType),
				authenticationPayload(tt.triggerSource))

			if err := validatePayload(event); err != nil {
				t.Errorf("expected %s to pass, got error: %v", tt.action, err)
			}
		})
	}

	withoutAppClient := authenticationPayload("PostAuthentication_Authentication")
	withoutAppClient.AppClientID = ""
	event := buildEvent(t, "AUTHENTICATION", "LOGIN", string(models.CognitoTriggersServiceType), withoutAppClient)
	if err := validatePayload(event); err != nil {
		t.Errorf("expected a login without an app client to pass, got error: %v", err)
	}
}

func TestValidatePayload_AuthenticationRejectsInvalidEvents(t *testing.T) {
	login := "PostAuthentication_Authentication"
	tests := []struct {
		name      string
		eventType string
		action    string
		actor     string
		change    func(*ValidAuthenticationPayload)
	}{
		{name: "wrong event type", eventType: "CRUD", action: "LOGIN", actor: "sub-1"},
		{name: "unknown action", eventType: "AUTHENTICATION", action: "LOGOUT", actor: "sub-1"},
		{name: "missing actor", eventType: "AUTHENTICATION", action: "LOGIN", actor: " "},
		{name: "missing user pool", eventType: "AUTHENTICATION", action: "LOGIN", actor: "sub-1",
			change: func(p *ValidAuthenticationPayload) { p.UserPoolID = "" }},
		{name: "login from the password reset trigger", eventType: "AUTHENTICATION", action: "LOGIN", actor: "sub-1",
			change: func(p *ValidAuthenticationPayload) { p.TriggerSource = "PostConfirmation_ConfirmForgotPassword" }},
		{name: "password reset from a sign-up confirmation", eventType: "AUTHENTICATION", action: "PASSWORD_RESET", actor: "sub-1",
			change: func(p *ValidAuthenticationPayload) { p.TriggerSource = "PostConfirmation_ConfirmSignUp" }},
		{name: "missing datetime", eventType: "AUTHENTICATION", action: "LOGIN", actor: "sub-1",
			change: func(p *ValidAuthenticationPayload) { p.DateTime = "" }},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			payload := authenticationPayload(login)
			if tt.change != nil {
				tt.change(&payload)
			}
			event := buildEvent(t, tt.eventType, tt.action, string(models.CognitoTriggersServiceType), payload)
			event.ActorID = tt.actor

			if err := validatePayload(event); err == nil {
				t.Errorf("expected %s to be rejected, got nil", tt.name)
			}
		})
	}
}

func TestValidatePayload_UnknownSourceService(t *testing.T) {
	event := buildEvent(t, "CRUD", "UPDATE", "NOT_A_REAL_SERVICE", ValidCRUDPayload{})

	if err := validatePayload(event); err == nil {
		t.Error("expected error for unknown source_service, got nil")
	}
}

func relationshipHealthEvent(action, entityType string, payload map[string]any) ValidEvent {
	base := map[string]any{
		"schema_version": "1", "entity_type": entityType, "entity_id": "opaque-task-1",
		"occurred_at": "2026-10-28T03:00:00Z", "correlation_id": "correlation-1",
		"client_ref": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	}
	for key, value := range payload { base[key] = value }
	encoded, _ := json.Marshal(base)
	return ValidEvent{EventID: strings.Repeat("b", 64), EventType: "RELATIONSHIP_HEALTH", Action: action,
		SourceService: string(models.RelationshipHealthServiceType), ActorID: "SYSTEM", Payload: encoded}
}

func TestValidatePayload_RelationshipHealthRedactedContract(t *testing.T) {
	actions := map[string]string{
		"ANALYSIS_RUN_STARTED": "ANALYSIS_RUN", "SIGNAL_DETECTED": "SIGNAL",
		"SIGNAL_SUPPRESSED": "FINDING", "TASK_CREATED": "RELATIONSHIP_TASK",
		"EXPLANATION_FALLBACK_USED": "EXPLANATION", "OUTBOX_DELIVERY_RECOVERED": "OUTBOX",
	}
	for action, entityType := range actions {
		t.Run(action, func(t *testing.T) {
			event := relationshipHealthEvent(action, entityType, map[string]any{
				"after": map[string]any{"status": "OPEN", "severity": "HIGH", "task_version": 1},
			})
			if err := validatePayload(event); err != nil { t.Fatalf("valid event rejected: %v", err) }
		})
	}
}

func TestValidatePayload_RelationshipHealthRejectsSensitiveOrMalformedPayload(t *testing.T) {
	tests := map[string]map[string]any{
		"raw transactions": {"transactions": []string{"transaction-1"}},
		"notes": {"note": "private note"},
		"descriptions": {"description": "merchant text"},
		"raw client identifier": {"client_id": "client-1"},
		"bad client reference": {"client_ref": "client-1"},
		"bad state": {"after": map[string]any{"status": "UNKNOWN", "task_version": 0}},
	}
	for name, extra := range tests {
		t.Run(name, func(t *testing.T) {
			event := relationshipHealthEvent("TASK_CREATED", "RELATIONSHIP_TASK", extra)
			if err := validatePayload(event); err == nil { t.Fatal("unsafe payload accepted") }
		})
	}
}

func TestValidatePayload_ServiceNotPermittedForEventType(t *testing.T) {
	// CRM service is only permitted CRUD, not TRANSACTION_INGESTION
	event := buildEvent(t, "TRANSACTION_INGESTION", "FILE_PROCESSING_COMPLETED", string(models.CRMServiceType), ValidFileCompletedPayload{
		FileName: "should-not-matter.csv",
	})

	if err := validatePayload(event); err == nil {
		t.Error("expected error when CRM service sends TRANSACTION_INGESTION event_type, got nil")
	}
}

func TestValidatePayload_UnrecognizedTransactionAction(t *testing.T) {
	event := buildEvent(t, "TRANSACTION_INGESTION", "SOME_UNKNOWN_ACTION", string(models.TransactionServiceType), ProcessingErrorPayload{
		FileName: "transactions_2026_09_18.csv",
	})

	if err := validatePayload(event); err == nil {
		t.Error("expected error for unrecognized transaction action, got nil")
	}
}

func TestValidatePayload_MalformedCRUDPayload(t *testing.T) {
	event := ValidEvent{
		EventType:     "CRUD",
		Action:        "UPDATE",
		SourceService: string(models.CRMServiceType),
		ActorID:       "test-actor",
		Payload:       json.RawMessage(`{"agent_id": 12345}`), // agent_id should be a string, not a number
	}

	if err := validatePayload(event); err == nil {
		t.Error("expected error for malformed CRUD payload (wrong field type), got nil")
	}
}

func TestValidatePayload_MalformedFileCompletedPayload(t *testing.T) {
	event := ValidEvent{
		EventType:     "TRANSACTION_INGESTION",
		Action:        "FILE_PROCESSING_COMPLETED",
		SourceService: string(models.TransactionServiceType),
		ActorID:       "test-actor",
		Payload:       json.RawMessage(`{"records_processed": "not-a-number"}`),
	}

	if err := validatePayload(event); err == nil {
		t.Error("expected error for malformed FileCompletedPayload (wrong field type), got nil")
	}
}

func TestValidatePayload_ValidEventAlert(t *testing.T) {
	event := buildEvent(t, "EVENT_ALERT", "GENERATED", string(models.EventAlertServiceType), ValidEventAlertPayload{
		EventID:      "70000000-0000-4000-8000-000000000001",
		EventVersion: 1,
		OccurredAt:   time.Now().UTC(),
		Data:         json.RawMessage(`{"alertId":"70000000-0000-4000-8000-000000000002"}`),
	})

	if err := validatePayload(event); err != nil {
		t.Errorf("expected valid event-alert audit event to pass, got error: %v", err)
	}
}

func TestValidatePayload_RejectsUnknownEventAlertAction(t *testing.T) {
	event := buildEvent(t, "EVENT_ALERT", "CLIENT_DATA_EXPORTED", string(models.EventAlertServiceType), ValidEventAlertPayload{
		EventID:      "70000000-0000-4000-8000-000000000001",
		EventVersion: 1,
		OccurredAt:   time.Now().UTC(),
		Data:         json.RawMessage(`{}`),
	})

	if err := validatePayload(event); err == nil {
		t.Error("expected unknown event-alert action to be rejected")
	}
}

func TestValidatePayload_RejectsIncompleteEventAlertMetadata(t *testing.T) {
	event := buildEvent(t, "EVENT_ALERT", "GENERATED", string(models.EventAlertServiceType), map[string]any{
		"event_version": 1,
		"data":          map[string]string{"alertId": "synthetic"},
	})

	if err := validatePayload(event); err == nil {
		t.Error("expected missing event ID and occurrence time to be rejected")
	}
}

// engagementEventCreate is a message as engagement-service's outbox relay
// sends it: the event-alert envelope with IDs only in data.
const engagementEventCreate = `{"event_id":"80000000-0000-4000-8000-000000000001","event_type":"ENGAGEMENT",` +
	`"action":"EVENT_CREATE","source_service":"ENGAGEMENT","actor_id":"4a1b2c3d-0000-4000-8000-000000000001",` +
	`"payload":{"event_id":"80000000-0000-4000-8000-000000000001","event_version":1,` +
	`"occurred_at":"2026-10-21T02:00:00Z","data":{"calendarEventId":"80000000-0000-4000-8000-000000000002"}}}`

func engagementPayload() map[string]any {
	return map[string]any{
		"event_id":      "80000000-0000-4000-8000-000000000001",
		"event_version": 1,
		"occurred_at":   "2026-10-21T02:00:00Z",
		"data":          map[string]string{"calendarEventId": "80000000-0000-4000-8000-000000000002"},
	}
}

func TestValidatePayload_EngagementMessage(t *testing.T) {
	var event ValidEvent
	if err := json.Unmarshal([]byte(engagementEventCreate), &event); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if err := validatePayload(event); err != nil {
		t.Errorf("expected engagement-service's message to pass, got error: %v", err)
	}
}

func TestValidatePayload_ValidEngagementActions(t *testing.T) {
	actions := []string{
		"EVENT_CREATE", "EVENT_UPDATE", "EVENT_CANCEL",
		"INVITE_SENT", "INVITE_FAILED",
		"REMINDER_SENT", "REMINDER_FAILED", "REMINDER_MISSED",
		"DRAFT_GENERATE", "DRAFT_DISCARD", "EMAIL_SEND", "EMAIL_SEND_FAILED",
		"PREFERENCES_UPDATE",
	}
	for _, action := range actions {
		t.Run(action, func(t *testing.T) {
			event := buildEvent(t, "ENGAGEMENT", action, string(models.EngagementServiceType), engagementPayload())

			if err := validatePayload(event); err != nil {
				t.Errorf("expected engagement %s to pass, got error: %v", action, err)
			}
		})
	}

	// The dispatcher's own events name "system" as the actor.
	event := buildEvent(t, "ENGAGEMENT", "REMINDER_SENT", string(models.EngagementServiceType), engagementPayload())
	event.ActorID = "system"
	if err := validatePayload(event); err != nil {
		t.Errorf("expected a dispatcher event to pass, got error: %v", err)
	}
}

func TestValidatePayload_EngagementRejectsInvalidEvents(t *testing.T) {
	tests := []struct {
		name      string
		eventType string
		action    string
		actor     string
		change    func(map[string]any)
	}{
		{name: "wrong event type", eventType: "CRUD", action: "EVENT_CREATE", actor: "a1"},
		{name: "unknown action", eventType: "ENGAGEMENT", action: "EMAIL_EXPORT", actor: "a1"},
		{name: "missing actor", eventType: "ENGAGEMENT", action: "EVENT_CREATE", actor: " "},
		{name: "missing event ID", eventType: "ENGAGEMENT", action: "EVENT_CREATE", actor: "a1",
			change: func(p map[string]any) { delete(p, "event_id") }},
		{name: "version below 1", eventType: "ENGAGEMENT", action: "EVENT_CREATE", actor: "a1",
			change: func(p map[string]any) { p["event_version"] = 0 }},
		{name: "missing occurrence time", eventType: "ENGAGEMENT", action: "EVENT_CREATE", actor: "a1",
			change: func(p map[string]any) { delete(p, "occurred_at") }},
		{name: "missing data", eventType: "ENGAGEMENT", action: "EVENT_CREATE", actor: "a1",
			change: func(p map[string]any) { delete(p, "data") }},
		{name: "data not an object", eventType: "ENGAGEMENT", action: "EVENT_CREATE", actor: "a1",
			change: func(p map[string]any) { p["data"] = "calendar event" }},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			payload := engagementPayload()
			if tt.change != nil {
				tt.change(payload)
			}
			event := buildEvent(t, tt.eventType, tt.action, string(models.EngagementServiceType), payload)
			event.ActorID = tt.actor

			if err := validatePayload(event); err == nil {
				t.Errorf("expected %s to be rejected, got nil", tt.name)
			}
		})
	}
}

func TestValidEvent_JSONIncludesEventID(t *testing.T) {
	raw := []byte(`{"event_id":"evt-123","event_type":"CRUD","action":"UPDATE","source_service":"CRM","actor_id":"a1","payload":{}}`)

	var event ValidEvent
	if err := json.Unmarshal(raw, &event); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if event.EventID != "evt-123" {
		t.Errorf("expected event_id %q to be parsed, got %q", "evt-123", event.EventID)
	}
}
