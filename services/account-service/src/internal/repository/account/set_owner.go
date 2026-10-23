package account

import (
	"context"

	orm "account-service/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

// SetOwner makes ownerAgentID the owner of every account of the clients,
// deleted ones included, so a deleted account never keeps an agent its client
// no longer has (ADR-0060). Setting the owner an account already has changes
// nothing, so a repeated reassignment is safe. It returns how many accounts
// were updated.
func (r accountRepository) SetOwner(ctx context.Context, clientIDs []string, ownerAgentID string) (int64, error) {
	if len(clientIDs) == 0 {
		return 0, nil
	}
	return orm.Accounts(qm.WithDeleted(), orm.AccountWhere.ClientID.IN(clientIDs)).
		UpdateAll(ctx, r.db, orm.M{orm.AccountColumns.OwnerAgentID: ownerAgentID})
}
