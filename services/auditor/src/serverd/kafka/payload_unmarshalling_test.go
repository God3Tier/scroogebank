package kafka

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
	event := buildEvent(t, "CRUD", "UPDATE", string(models.CRMServiceType), ValidCRUDPayload{
		AttributeName: "First Name|Address",
		BeforeValue:   "LEE|ABC",
		AfterValue:    "TAN|XX",
		AgentID:       "AGT-001",
		ClientID:      "CLI-001",
		DateTime:      time.Now().Format(time.RFC3339),
	})

	if err := validatePayload(event); err != nil {
		t.Errorf("expected valid CRUD event from CRM service to pass, got error: %v", err)
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
