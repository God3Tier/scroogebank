package models

import "time"

type CorruptedAuditLog struct {
	ID string `json:"id"`
	// AuditLogID and Reason are set on the findings chain-verify records
	// (ADR-0022 decision 4), and empty on those added through
	// POST /api/corrupted-logs, which does not set them.
	AuditLogID   string        `json:"audit_log_id,omitempty"`
	Reason       FindingReason `json:"reason,omitempty"`
	Payload      AuditLog      `json:"payload"`
	DateOfDetect time.Time     `json:"date_of_detect"`
}
