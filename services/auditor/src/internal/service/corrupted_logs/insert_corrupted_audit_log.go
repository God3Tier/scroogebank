package corrupted_log

import (
	"auditor/src/internal/models"
	"context"
)

func (i impl) InsertCorruptedAuditLog(ctx context.Context, raw models.CorruptedAuditLog) error {
	return i.repo.InsertCorruptedLog(ctx, raw)
}
