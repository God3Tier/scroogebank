package audit

import (
	"auditor/src/internal/models"
	"context"
)

func (i impl) DeleteAuditLog(ctx context.Context, hash string) (models.AuditLog, error) {
	return i.repo.RemoveAuditLog(ctx, hash)
}
