package poller

import (
	"context"
	"time"

	"receiver/src/internal/models"
)

// AuditEvents builds ingestion audit events in the transaction audit contract.
type AuditEvents interface {
	FileCompleted(FileCompleted) (models.AuditEvent, error)
	ProcessingError(ProcessingError) (models.AuditEvent, error)
}

// AuditRelay delivers saved audit events to the Auditor (ADR-0020 decision 3).
type AuditRelay interface {
	// RelayOnce sends every waiting event; any it cannot send wait for the
	// next poll.
	RelayOnce(context.Context) error
	// Send delivers one event straight away, for an event that could not be
	// saved.
	Send(context.Context, models.AuditEvent) error
}

// Audit turns ingestion auditing on. Both parts are required.
type Audit struct {
	Events AuditEvents
	Relay  AuditRelay
}

type FileCompleted struct {
	FileName         string
	ProcessingStart  time.Time
	ProcessingEnd    time.Time
	Status           string
	RecordsProcessed int
	RecordsAccepted  int
	RecordsRejected  int
	ParseFailures    []ParseFailure
}

type ParseFailure struct {
	RecordRef string
	Reason    string
}

type ProcessingError struct {
	FileName   string
	Action     string
	Stage      string
	Error      string
	OccurredAt time.Time // when the failure's episode began
}
