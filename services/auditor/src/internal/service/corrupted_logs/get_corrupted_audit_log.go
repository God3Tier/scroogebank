package corrupted_log

import (
	"auditor/src/internal/models"
	"context"
)

func (i impl) GetCorruptedAuditLog(ctx context.Context) ([]models.CorruptedAuditLog, error) {
	return i.repo.GetCorruptedLogs(ctx)
}
