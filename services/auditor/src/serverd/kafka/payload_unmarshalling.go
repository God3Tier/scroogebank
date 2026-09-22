package kafka

import (
	"auditor/src/internal/models"
	"encoding/json"
	"fmt"
	"time"
)

type ValidEvent struct {
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

func validatePayload(envelope ValidEvent) error {
	svc := models.ServiceType(envelope.SourceService)
	if !svc.IsValid() {
		return fmt.Errorf("unknown source_service: %s", envelope.SourceService)
	}
	if !svc.IsValidAction(envelope.EventType) {
		return fmt.Errorf("%s is not permitted to send event_type %s",
			envelope.SourceService, envelope.EventType)
	}

	switch svc {
	case models.TransactionServiceType:
		return validateTransactionPayload(envelope)

	case models.CRMServiceType, models.UserManagementServiceType:
		var p ValidCRUDPayload
		if err := json.Unmarshal(envelope.Payload, &p); err != nil {
			return fmt.Errorf("invalid CRUDPayload: %w", err)
		}
		return nil

	default:
		return fmt.Errorf("unhandled source_service: %s", envelope.SourceService)
	}
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
