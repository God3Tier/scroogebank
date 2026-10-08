package account

import (
	"context"

	orm "account-service/src/internal/repository/orm"
)


func (r accountRepository) DeleteAccount(ctx context.Context, accountID string) error {
	account, err := orm.FindAccount(ctx, r.db, accountID)
	if err != nil {
		return err
	}

	_, err = account.Delete(ctx, r.db)
	return err
}
