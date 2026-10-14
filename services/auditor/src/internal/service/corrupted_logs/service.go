package corrupted_log

import (
	"context"

	"auditor/src/internal/models"
	"auditor/src/internal/repository/corrupted_log"
)

type Service interface {
	GetCorruptedAuditLog(ctx context.Context) ([]models.CorruptedAuditLog, error)
	InsertCorruptedAuditLog(ctx context.Context, raw models.CorruptedAuditLog) error
	// RecordFinding records a problem chain-verify found, once per row and
	// reason, and reports whether it was new (ADR-0022 decision 4).
	RecordFinding(ctx context.Context, finding models.ChainFinding) (bool, error)
	DeleteCorruptedAuditLog(ctx context.Context, uuid string) error
}

type impl struct {
	repo corrupted_log.Repository
}

func New(repo corrupted_log.Repository) Service {
	return impl{repo: repo}
}
