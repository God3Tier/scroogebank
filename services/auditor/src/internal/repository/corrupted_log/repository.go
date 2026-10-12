package corrupted_log

import (
	"auditor/src/internal/models"
	"context"
	"database/sql"
)

type Repository interface {
	InsertCorruptedLog(ctx context.Context, raw models.CorruptedAuditLog) error
	// RecordFinding records a problem chain-verify found, once per row and
	// reason, and reports whether it was new.
	RecordFinding(ctx context.Context, finding models.ChainFinding) (bool, error)
	GetCorruptedLogs(ctx context.Context) ([]models.CorruptedAuditLog, error)
	DeleteCorruptedLog(ctx context.Context, uuid string) error
}

type impl struct {
	db *sql.DB
}

func New(db *sql.DB) Repository {
	return impl{
		db,
	}
}
