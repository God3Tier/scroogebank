package audit

import (
	"auditor/src/internal/models"
	"context"
)

func (i impl) ListActivities(ctx context.Context, filter models.ActivityFilter) ([]models.AuditLog, error) {
	return i.repo.ListActivities(ctx, filter)
}
