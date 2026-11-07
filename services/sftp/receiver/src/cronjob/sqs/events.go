package sqs

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"time"

	"receiver/src/cronjob/poller"
	"receiver/src/internal/models"
)

const (
	transactionEventType = "TRANSACTION_INGESTION"
	transactionSource    = "TRANSACTION_SERVICE"
	transactionActor     = "sftp-poller"
)

// Events builds ingestion audit events in the transaction audit contract. The
// poller saves them to audit_outbox and Relay sends them (ADR-0020 decision 3).
type Events struct{}

type event struct {
	EventID       string          `json:"event_id"`
	EventType     string          `json:"event_type"`
	Action        string          `json:"action"`
	SourceService string          `json:"source_service"`
	ActorID       string          `json:"actor_id"`
	Payload       json.RawMessage `json:"payload"`
}

type completedPayload struct {
	FileName         string    `json:"file_name"`
	ProcessingStart  time.Time `json:"processing_start"`
	ProcessingEnd    time.Time `json:"processing_end"`
	Status           string    `json:"status"`
	RecordsProcessed int       `json:"records_processed"`
	RecordsAccepted  int       `json:"records_accepted"`
	RecordsRejected  int       `json:"records_rejected"`
	ParseFailures    []failure `json:"parse_failures,omitempty"`
}

type failure struct {
	RecordRef string `json:"record_ref"`
	Reason    string `json:"reason"`
}

// processingErrorPayload records one failure episode. occurred_at is when it
// began, so a later episode of the same failure gets its own event ID instead
// of being dropped by the Auditor as a repeat.
type processingErrorPayload struct {
	FileName   string    `json:"file_name"`
	Stage      string    `json:"stage"`
	Error      string    `json:"error"`
	OccurredAt time.Time `json:"occurred_at"`
}

func (Events) FileCompleted(result poller.FileCompleted) (models.AuditEvent, error) {
	failures := make([]failure, 0, len(result.ParseFailures))
	for _, item := range result.ParseFailures {
		failures = append(failures, failure{RecordRef: item.RecordRef, Reason: item.Reason})
	}
	payload := completedPayload{
		FileName: result.FileName, ProcessingStart: result.ProcessingStart,
		ProcessingEnd: result.ProcessingEnd,
		Status:        result.Status, RecordsProcessed: result.RecordsProcessed, RecordsAccepted: result.RecordsAccepted,
		RecordsRejected: result.RecordsRejected, ParseFailures: failures,
	}
	return encode("FILE_PROCESSING_COMPLETED", result.FileName, payload)
}

func (Events) ProcessingError(item poller.ProcessingError) (models.AuditEvent, error) {
	payload := processingErrorPayload{FileName: item.FileName, Stage: item.Stage, Error: item.Error, OccurredAt: item.OccurredAt}
	return encode(item.Action, item.FileName, payload)
}

func encode(action, identity string, payload any) (models.AuditEvent, error) {
	encodedPayload, err := json.Marshal(payload)
	if err != nil {
		return models.AuditEvent{}, fmt.Errorf("marshal audit payload: %w", err)
	}
	id := eventID(action, identity, encodedPayload)
	body, err := json.Marshal(event{
		EventID: id, EventType: transactionEventType, Action: action,
		SourceService: transactionSource, ActorID: transactionActor, Payload: encodedPayload,
	})
	if err != nil {
		return models.AuditEvent{}, fmt.Errorf("marshal audit event: %w", err)
	}
	return models.AuditEvent{ID: id, Body: body}, nil
}

func eventID(action, identity string, payload []byte) string {
	hash := sha256.New()
	_, _ = hash.Write([]byte(transactionSource + "\x00" + action + "\x00" + identity + "\x00"))
	_, _ = hash.Write(payload)
	return hex.EncodeToString(hash.Sum(nil))
}
