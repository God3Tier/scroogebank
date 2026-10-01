package queue

import (
	"auditor/src/internal/models"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

type ValidEvent struct {
	// EventID identifies this event for de-duplication. SQS standard queues
	// deliver at-least-once, so the same message can be received more than
	// once; the auditor uses EventID to skip an event it has already stored.
	EventID       string          `json:"event_id"`
	EventType     string          `json:"event_type"`
	Action        string          `json:"action"`
	SourceService string          `json:"source_service"`
	ActorId       string          `json:"actor_id"`
	Payload       json.RawMessage `json:"payload"`
}

type ValidCRUDPayload struct {
	AttributeName string `json:"attribute_name"`
	BeforeValue   string `json:"before_value"`
	AfterValue    string `json:"after_value"`
	AgentID       string `json:"agent_id"`
	ClientID      string `json:"client_id"`
	DateTime      string `json:"datetime"`
}

// ValidUserAuditPayload describes user-service endpoint activity. It records
// the target user and operation metadata without copying names, email
// addresses, passwords, or request bodies into the audit chain.
type ValidUserAuditPayload struct {
	ResourceType string `json:"resource_type"`
	ResourceID   string `json:"resource_id"`
	Endpoint     string `json:"endpoint"`
	HTTPMethod   string `json:"http_method"`
	StatusCode   int    `json:"status_code"`
	DateTime     string `json:"datetime"`
	UserRole     string `json:"user_role,omitempty"`
}

type ValidFileCompletedPayload struct {
	FileName         string         `json:"file_name"`
	ProcessingStart  time.Time      `json:"processing_start"`
	ProcessingEnd    time.Time      `json:"processing_end"`
	Status           string         `json:"status"`
	RecordsProcessed int            `json:"records_processed"`
	RecordsAccepted  int            `json:"records_accepted"`
	RecordsRejected  int            `json:"records_rejected"`
	ParseFailures    []ParseFailure `json:"parse_failures,omitempty"`
}

type ParseFailure struct {
	RecordRef string `json:"record_ref"` // identifies which record this was
	Reason    string `json:"reason"`     // why it was rejected
}

type ProcessingErrorPayload struct {
	FileName string `json:"file_name"`
	Stage    string `json:"stage"` // "FILE_FORMAT" | "CONNECTION" | "SYSTEM_EXCEPTION"
	Error    string `json:"error"`
}

type ValidEventAlertPayload struct {
	EventID      string          `json:"event_id"`
	EventVersion int             `json:"event_version"`
	OccurredAt   time.Time       `json:"occurred_at"`
	Data         json.RawMessage `json:"data"`
}

func validatePayload(envelope ValidEvent) error {
	svc := models.ServiceType(envelope.SourceService)
	if !svc.IsValid() {
		return fmt.Errorf("unknown source_service: %s", envelope.SourceService)
	}
	if svc == models.CRMServiceType {
		if envelope.EventType != "CRUD" {
			return fmt.Errorf("%s is not permitted to send event_type %s",
				envelope.SourceService, envelope.EventType)
		}
		if !svc.IsValidAction(envelope.Action) {
			return fmt.Errorf("%s is not permitted to send action %s",
				envelope.SourceService, envelope.Action)
		}
	} else if !svc.IsValidAction(envelope.EventType) {
		return fmt.Errorf("%s is not permitted to send event_type %s",
			envelope.SourceService, envelope.EventType)
	}

	switch svc {
	case models.TransactionServiceType:
		return validateTransactionPayload(envelope)

	case models.UserManagementServiceType:
		return validateUserManagementPayload(envelope)

	case models.CRMServiceType:
		var p ValidCRUDPayload
		if err := json.Unmarshal(envelope.Payload, &p); err != nil {
			return fmt.Errorf("invalid CRUDPayload: %w", err)
		}
		return nil

	case models.EventAlertServiceType:
		return validateEventAlertPayload(envelope)

	default:
		return fmt.Errorf("unhandled source_service: %s", envelope.SourceService)
	}
}

func validateEventAlertPayload(envelope ValidEvent) error {
	allowedActions := map[string]bool{
		"CLASSIFICATION_RECORDED":     true,
		"BRIEFING_GENERATED":          true,
		"GENERATED":                   true,
		"DECISION_RECORDED":           true,
		"FEEDBACK_RECORDED":           true,
		"OPERATIONAL_CONTROL_CHANGED": true,
	}
	if !allowedActions[envelope.Action] {
		return fmt.Errorf("unrecognized action for EVENT_ALERT_SERVICE: %s", envelope.Action)
	}

	var payload ValidEventAlertPayload
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return fmt.Errorf("invalid EventAlertPayload: %w", err)
	}
	if payload.EventID == "" || payload.EventVersion < 1 || payload.OccurredAt.IsZero() || len(payload.Data) == 0 {
		return fmt.Errorf("invalid EventAlertPayload: required audit metadata is missing")
	}
	return nil
}

func validateUserManagementPayload(envelope ValidEvent) error {
	if !models.UserManagementServiceType.IsValidAction(envelope.Action) || strings.EqualFold(envelope.Action, "CRUD") {
		return fmt.Errorf("unrecognized action for USER_MANAGEMENT: %s", envelope.Action)
	}
	if strings.TrimSpace(envelope.ActorId) == "" {
		return fmt.Errorf("USER_MANAGEMENT event requires actor_id")
	}
	var payload ValidUserAuditPayload
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return fmt.Errorf("invalid UserAuditPayload: %w", err)
	}
	if payload.ResourceType == "" {
		// Accept the previous generic CRUD payload during rollout so messages
		// already queued before the user-service deployment remain consumable.
		var legacy ValidCRUDPayload
		if err := json.Unmarshal(envelope.Payload, &legacy); err != nil {
			return fmt.Errorf("invalid legacy CRUDPayload: %w", err)
		}
		return nil
	}
	if payload.ResourceType != "USER" || (payload.ResourceID == "" && payload.StatusCode < 400) || payload.Endpoint == "" ||
		payload.HTTPMethod == "" || payload.StatusCode < 100 || payload.StatusCode > 599 {
		return fmt.Errorf("invalid UserAuditPayload: missing or invalid user operation fields")
	}
	if _, err := time.Parse(time.RFC3339Nano, payload.DateTime); err != nil {
		return fmt.Errorf("invalid UserAuditPayload datetime: %w", err)
	}
	return nil
}

func validateTransactionPayload(envelope ValidEvent) error {
	switch envelope.Action {
	case "FILE_PROCESSING_COMPLETED":
		var p ValidFileCompletedPayload
		if err := json.Unmarshal(envelope.Payload, &p); err != nil {
			return fmt.Errorf("invalid FileCompletedPayload: %w", err)
		}
		return nil

	case "FILE_FORMAT_INVALID", "CONNECTION_FAILURE", "SYSTEM_EXCEPTION":
		var p ProcessingErrorPayload
		if err := json.Unmarshal(envelope.Payload, &p); err != nil {
			return fmt.Errorf("invalid ProcessingErrorPayload: %w", err)
		}
		return nil

	default:
		return fmt.Errorf("unrecognized action for TRANSACTION_SERVICE: %s", envelope.Action)
	}
}
