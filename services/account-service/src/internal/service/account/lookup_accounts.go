package account

import (
	accounterrors "account-service/src/internal/errors"
	orm "account-service/src/internal/repository/orm"
	"context"
	"fmt"
	"uuid"
)

// MaxLookupIDs bounds one lookup request; the import sends larger files in batches.
const MaxLookupIDs = 500

// LookupAccounts ignores IDs that are not UUIDs: imported files can carry
// external references, which can never match an account.
func (s *accountService) LookupAccounts(ctx context.Context, ids []string) (orm.AccountSlice, error) {
	if len(ids) > MaxLookupIDs {
		return nil, fmt.Errorf("%w: at most %d ids per lookup", accounterrors.ErrBadInput, MaxLookupIDs)
	}
	valid := make([]string, 0, len(ids))
	for _, id := range ids {
		if _, err := uuid.Parse(id); err == nil {
			valid = append(valid, id)
		}
	}
	return s.repo.FindAccountsByIDs(ctx, valid)
}
