package models

import (
	"time"
)

type AuditLog struct {
	Id            string
	SequenceNo    int64
	EventType     string
	Action        string
	ActorId       string
	SourceService ServiceType
	Timestamp     time.Time
	Payload       string
	ParentHash    string
	Hash          string
}
