package poller

import (
	"context"
	"time"
)

// AuditWriter publishes ingestion outcomes using the transaction audit contract.
type AuditWriter interface {
	WriteFileCompleted(context.Context, FileCompleted) error
	WriteProcessingError(context.Context, ProcessingError) error
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
	FileName string
	Action   string
	Stage    string
	Error    string
}
