package models

import (
	"time"
)

type AuditLog struct {
	ID            string
	SequenceNo    int64
	EventID       string
	EventType     string
	Action        string
	ActorID       string
	SourceService ServiceType
	Timestamp     time.Time
	Payload       string
	ParentHash    string
	Hash          string
}
