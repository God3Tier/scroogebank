package audit

import (
	"context"
	"database/sql"

	"auditor/src/internal/models"
)

type Repository interface {
	GetAuditByService(ctx context.Context, service models.ServiceType) ([]models.AuditLog, error)
	StoreAuditChain(ctx context.Context, auditRequest models.AuditLogInput) error
	GetChain(ctx context.Context) ([]models.AuditLog, error)
	GetChainByOffset(ctx context.Context, startChain int, offset int) ([]models.AuditLog, error)
	RemoveAuditLog(ctx context.Context, hash string) (models.AuditLog, error)
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
