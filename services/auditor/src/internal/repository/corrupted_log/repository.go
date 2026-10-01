package corrupted_log

import (
	"auditor/src/internal/models"
	"context"
	"database/sql"
)

type Repository interface {
	InsertCorruptedLog(ctx context.Context, raw models.CorruptedAuditLog) error 
	GetCorruptedLogs(ctx context.Context)( []models.CorruptedAuditLog, error)
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
