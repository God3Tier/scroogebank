package account

import (
	"context"
	"testing"

	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	repomock "account-service/src/internal/repository/account"
	orm "account-service/src/internal/repository/orm"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestListAccounts(t *testing.T) {
	ctx := context.Background()
	const clientID = "00000000-0000-0000-0000-000000000203"
	accounts := orm.AccountSlice{{ID: "00000000-0000-0000-0000-000000000231"}}

	t.Run("an admin sees every account of the client", func(t *testing.T) {
		repo := repomock.NewMockRepository(t)
		repo.On("ListAccountsByClient", ctx, clientID).Return(accounts, nil).Once()

		got, err := NewAccountService(repo, nil).ListAccounts(ctx, clientID, models.Caller{Subject: "admin-sub", Admin: true})

		require.NoError(t, err)
		assert.Equal(t, accounts, got)
	})

	t.Run("an agent sees only accounts they own", func(t *testing.T) {
		repo := repomock.NewMockRepository(t)
		repo.On("ListAccountsByClientAndOwner", ctx, clientID, "agent-sub").Return(accounts, nil).Once()

		got, err := NewAccountService(repo, nil).ListAccounts(ctx, clientID, models.Caller{Subject: "agent-sub"})

		require.NoError(t, err)
		assert.Equal(t, accounts, got)
	})

	t.Run("an agent without a subject is refused, not given an unscoped list", func(t *testing.T) {
		repo := repomock.NewMockRepository(t)

		_, err := NewAccountService(repo, nil).ListAccounts(ctx, clientID, models.Caller{})

		assert.ErrorIs(t, err, accounterrors.ErrBadInput)
	})

	t.Run("a blank client ID is refused", func(t *testing.T) {
		repo := repomock.NewMockRepository(t)

		_, err := NewAccountService(repo, nil).ListAccounts(ctx, " ", models.Caller{Admin: true})

		assert.ErrorIs(t, err, accounterrors.ErrBadInput)
	})
}
