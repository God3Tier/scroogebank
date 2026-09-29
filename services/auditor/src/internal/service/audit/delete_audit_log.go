package audit

import "context"

func (i impl) DeleteAuditLog(ctx context.Context, hash string) error {
	return i.repo.RemoveAuditLog(ctx, hash)
}
