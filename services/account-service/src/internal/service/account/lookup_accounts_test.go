package account

import (
	"context"
	"fmt"
	"testing"

	accounterrors "account-service/src/internal/errors"
	repomock "account-service/src/internal/repository/account"
	orm "account-service/src/internal/repository/orm"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestLookupAccounts(t *testing.T) {
	ctx := context.Background()
	const accountID = "00000000-0000-0000-0000-000000000231"

	t.Run("looks up only the IDs that are UUIDs", func(t *testing.T) {
		repo := repomock.NewMockRepository(t)
		found := orm.AccountSlice{{ID: accountID}}
		repo.On("FindAccountsByIDs", ctx, []string{accountID}).Return(found, nil).Once()

		got, err := NewAccountService(repo, nil).LookupAccounts(ctx, []string{accountID, "ACC-000123", ""})

		require.NoError(t, err)
		assert.Equal(t, found, got)
	})

	t.Run("refuses more IDs than one lookup allows", func(t *testing.T) {
		repo := repomock.NewMockRepository(t)
		ids := make([]string, MaxLookupIDs+1)
		for i := range ids {
			ids[i] = fmt.Sprintf("00000000-0000-0000-0000-%012d", i)
		}

		_, err := NewAccountService(repo, nil).LookupAccounts(ctx, ids)

		assert.ErrorIs(t, err, accounterrors.ErrBadInput)
	})
}
