package account

import "context"

// DeleteAccount soft-deletes: it sets deleted_at and keeps the row (ADR-0036).
func (r accountRepository) DeleteAccount(ctx context.Context, accountID string) error {
	account, err := r.GetAccount(ctx, accountID)
	if err != nil {
		return err
	}

	_, err = account.Delete(ctx, r.db, false) // false: soft delete
	return err
}
