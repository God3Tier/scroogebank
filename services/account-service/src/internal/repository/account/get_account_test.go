package account

import (
	"context"
	"testing"

	clienterrors "account-service/src/internal/errors"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGetAccount(t *testing.T) {
	// Each case looks up the account its fixture sets up: account_empty.sql
	// removes ...0299, while account_lookup.sql (loaded by the first case)
	// leaves ...0202 in the shared test database.
	tests := []struct {
		name      string
		fixture   string
		accountID string
		wantFound bool
	}{
		{name: "returns existing account", fixture: "account_lookup.sql", accountID: "00000000-0000-0000-0000-000000000202", wantFound: true},
		{name: "returns not found for missing account", fixture: "account_empty.sql", accountID: "00000000-0000-0000-0000-000000000299"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)
			got, err := NewAccountRepository(db).GetAccount(context.Background(), test.accountID)
			if !test.wantFound {
				require.ErrorIs(t, err, clienterrors.ErrNotFound)
				assert.Nil(t, got)
				return
			}
			require.NoError(t, err)
			require.NotNil(t, got)
			assert.Equal(t, test.accountID, got.ID)
		})
	}
}

func TestGetAccountHidesDeletedAccount(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "account_deleted.sql")

	got, err := NewAccountRepository(db).GetAccount(context.Background(), "00000000-0000-0000-0000-000000000206")

	require.ErrorIs(t, err, clienterrors.ErrNotFound)
	assert.Nil(t, got)
}
