package account

import (
	"context"
	"database/sql"
	"errors"
	"strings"

	accounterrors "account-service/src/internal/errors"
	orm "account-service/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

// UpdateAccountStatus sets an account's status and returns the one it had
// (ADR-0050). The row is locked while it is read and updated, so the status
// returned is the one this update replaced even when two updates race, and
// the audit event's before value is exact. Deleted accounts are not found
// (ADR-0036).
func (r accountRepository) UpdateAccountStatus(ctx context.Context, accountID, status string) (string, error) {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()

	// The generated query adds deleted_at IS NULL.
	account, err := orm.Accounts(orm.AccountWhere.ID.EQ(accountID), qm.For("UPDATE")).One(ctx, tx)
	if errors.Is(err, sql.ErrNoRows) {
		return "", accounterrors.ErrNotFound
	}
	if err != nil {
		return "", err
	}

	previous := account.Status
	// Stored in lower case, as CreateAccount stores it.
	account.Status = strings.ToLower(status)
	if _, err := account.Update(ctx, tx, boil.Whitelist(orm.AccountColumns.Status, orm.AccountColumns.UpdatedAt)); err != nil {
		return "", err
	}
	return previous, tx.Commit()
}
