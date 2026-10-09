package audit

import (
	"auditor/src/internal/models"
	"auditor/src/internal/repository/audit"
	"context"
)

// Service is the placeholder for auditor business logic.
type Service interface {
	// Retrieve audit log per service
	RetrieveAuditLog(ctx context.Context, serviceType models.ServiceType) ([]RetrieveAuditLogResponse, error)
	VerifyChain(ctx context.Context) (map[string]error, error)
	VerifyChainByOffset(ctx context.Context, beginChain int, offset int) (map[string]error, error)
	DeleteAuditLog(ctx context.Context, hash string) (models.AuditLog, error)
	// List audit entries matching a filter, newest first
	ListAuditLogs(ctx context.Context, filter models.AuditLogFilter) ([]models.AuditLog, error)
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
