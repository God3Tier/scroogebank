package corrupted_log

import (
	"auditor/src/internal/models"
	"context"
)

func (i impl) RecordFinding(ctx context.Context, finding models.ChainFinding) (bool, error) {
	return i.repo.RecordFinding(ctx, finding)
}
