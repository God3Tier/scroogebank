package service

import (
	"auditor/src/internal/models"
	"context"
)

func (i impl) ListAuditLogs(ctx context.Context, filter models.AuditLogFilter) ([]models.AuditLog, error) {
	return i.repo.ListAuditLogs(ctx, filter)
}
