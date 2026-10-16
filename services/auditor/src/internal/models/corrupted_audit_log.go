package models

import "time"

type CorruptedAuditLog struct {
	ID string
	// AuditLogID and Reason are set on the findings chain-verify records
	// (ADR-0022 decision 4), and empty on those added through
	// POST /api/corrupted-logs, which does not set them.
	AuditLogID   string
	Reason       FindingReason
	Payload      AuditLog
	DateOfDetect time.Time
}
