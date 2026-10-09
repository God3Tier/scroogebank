package queue

import (
	"auditor/src/internal/models"
	"encoding/json"
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
		ActorId:       "test-actor",
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
			event.ActorId = tt.actor

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
		ActorId:       "test-actor",
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
		ActorId:       "test-actor",
		Payload:       json.RawMessage(`{"records_processed": "not-a-number"}`),
	}

	if err := validatePayload(event); err == nil {
		t.Error("expected error for malformed FileCompletedPayload (wrong field type), got nil")
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
