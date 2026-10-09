package corrupted_log

import "context"

func (i impl) DeleteCorruptedAuditLog(ctx context.Context, uuid string) error {
	return i.repo.DeleteCorruptedLog(ctx, uuid)
}
