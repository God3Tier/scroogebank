package account

import (
	"context"
	"database/sql"
	"errors"

	clienterrors "account-service/src/internal/errors"
	orm "account-service/src/internal/repository/orm"
)

func (r accountRepository) GetAccount(ctx context.Context, accountID string) (*orm.Account, error) {
	account, err := orm.FindAccount(ctx, r.db, accountID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, clienterrors.ErrNotFound
		}
		return nil, err
	}
	return account, nil
}
