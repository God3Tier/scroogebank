package audit

import (
	"auditor/src/internal/models"
	"auditor/src/internal/repository/audit"
	"context"
)

// Service is the placeholder for auditor business logic.
type Service interface {
	VerifyChain(ctx context.Context) (map[string]error, error)
	VerifyChainByOffset(ctx context.Context, beginChain int, offset int) (map[string]error, error)
	DeleteAuditLog(ctx context.Context, hash string) (models.AuditLog, error)
	// ListActivities returns the activity feed's entries matching filter, newest first.
	ListActivities(ctx context.Context, filter models.ActivityFilter) ([]models.AuditLog, error)
}

// TODO: Figure out specifically what is needed for this service
type impl struct {
	repo audit.Repository
}

func New(repo audit.Repository) Service {
	return impl{
		repo,
	}
}
