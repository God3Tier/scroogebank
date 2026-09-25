package models

import "time"

// AuditLogFilter selects audit entries for ListAuditLogs. A zero value in any
// field means "no restriction" on that field.
type AuditLogFilter struct {
	SourceService    ServiceType
	ActorId          string
	From             time.Time // inclusive
	To               time.Time // exclusive
	BeforeSequenceNo int64     // only entries with a lower sequence_no
	Limit            int
}
