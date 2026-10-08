package account

import (
	orm "account-service/src/internal/repository/orm"
	"context"
	"database/sql"
	"fmt"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

func (r accountRepository) ListAccountsByClient(ctx context.Context, clientID string) (orm.AccountSlice, error) {
	return listAccounts(ctx, r.db, qm.Where(`"accounts"."client_id" = ?`, clientID))
}

func (r accountRepository) ListAccountsByClientAndOwner(ctx context.Context, clientID, ownerAgentID string) (orm.AccountSlice, error) {
	return listAccounts(ctx, r.db,
		qm.Where(`"accounts"."client_id" = ?`, clientID),
		qm.And(`"accounts"."owner_agent_id" = ?`, ownerAgentID),
	)
}

func listAccounts(ctx context.Context, db *sql.DB, mods ...qm.QueryMod) (orm.AccountSlice, error) {
	mods = append(mods, qm.OrderBy(`"accounts"."opening_date", "accounts"."id"`))
	accounts, err := orm.Accounts(mods...).All(ctx, db)
	if err != nil {
		return nil, fmt.Errorf("list accounts: %w", err)
	}
	if accounts == nil {
		// Encode as [] rather than null, so callers can count without a nil check.
		return orm.AccountSlice{}, nil
	}
	return accounts, nil
}
