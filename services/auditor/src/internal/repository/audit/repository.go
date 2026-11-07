package audit

import (
	"context"
	"database/sql"

	"auditor/src/internal/models"
)

// Repository appends to the audit chain and reads it. It never updates or
// deletes an entry: the auditor's database role may only insert into and read
// audit_log (ADR-0022 decision 2).
type Repository interface {
	StoreAuditChain(ctx context.Context, auditRequest models.AuditLogInput) error
	// GetChainPage returns up to limit entries with a sequence number above
	// afterSequenceNo, oldest first.
	GetChainPage(ctx context.Context, afterSequenceNo int64, limit int) ([]models.AuditLog, error)
	ListActivities(ctx context.Context, filter models.ActivityFilter) ([]models.AuditLog, error)
	ListAuditLogs(ctx context.Context, filter models.AuditLogFilter) ([]models.AuditLog, error)
}

type impl struct {
	db *sql.DB
}

func New(db *sql.DB) Repository {
	return impl{
		db,
	}
}
