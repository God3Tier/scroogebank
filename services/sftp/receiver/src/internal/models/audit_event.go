package models

// AuditEvent is one serialized audit event, keyed by the event_id the Auditor
// uses to drop repeats (ADR-0020).
type AuditEvent struct {
	ID   string
	Body []byte
}
