package models

// AuditLogFilter pages the complete audit trail, newest first. It deliberately
// has no actor filter: only administrators may call the complete-log RPC.
type AuditLogFilter struct {
	BeforeSequenceNo int64
	Limit            int
}
