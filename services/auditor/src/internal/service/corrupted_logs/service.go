package corrupted_log

import (
	"auditor/src/internal/models"
	"auditor/src/internal/repository/corrupted_log"
	"context"
)

type Service interface {
	GetCorruptedAuditLog(ctx context.Context) ([]models.CorruptedAuditLog, error)
	InsertCorruptedAuditLog(ctx context.Context, raw models.CorruptedAuditLog) error
	DeleteCorruptedAuditLog(ctx context.Context, uuid string) error
}

type impl struct {
	repo corrupted_log.Repository
}

func New(repo corrupted_log.Repository) Service {
	return impl{repo: repo}
}
