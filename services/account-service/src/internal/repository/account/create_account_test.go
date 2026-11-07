package account

import (
	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	"context"
	"testing"
	"time"
	"uuid"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestOpeningDateIsTheSingaporeDate(t *testing.T) {
	tests := map[string]struct {
		now  time.Time
		want string
	}{
		"before 08:00 in Singapore, UTC is still on the previous day": {
			now: time.Date(2026, 10, 15, 23, 30, 0, 0, time.UTC), want: "2026-10-16",
		},
		"after 08:00 in Singapore, both are on the same day": {
			now: time.Date(2026, 10, 16, 1, 0, 0, 0, time.UTC), want: "2026-10-16",
		},
		"the last second of a Singapore day": {
			now: time.Date(2026, 10, 16, 15, 59, 59, 0, time.UTC), want: "2026-10-16",
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			got := openingDate(test.now)
			assert.Equal(t, test.want, got.Format(time.DateOnly))
			assert.Equal(t, time.UTC, got.Location())
		})
	}
}

func TestCreateAccount(t *testing.T) {
	tests := map[string]struct {
		fixture        string
		initialDeposit float64
		wantError      bool
		wantErrorIs    error
	}{
		"persists a new account":                   {fixture: "account_client.sql", initialDeposit: 250.75},
		"rejects a negative initial deposit":       {fixture: "account_client.sql", initialDeposit: -1, wantError: true},
		"reopens a type whose account was deleted": {fixture: "account_reopen.sql", initialDeposit: 10},
		"refuses a second account of a type the client holds": {
			fixture: "account_duplicate.sql", initialDeposit: 10, wantError: true, wantErrorIs: accounterrors.ErrDuplicateAccount,
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)
			clientID, err := uuid.Parse("00000000-0000-0000-0000-000000000201")
			require.NoError(t, err)
			input := models.CreateAccountInput{
				ClientID: clientID, OwnerAgentID: "agent-sub-901", Type: models.AccountTypeChecking,
				Status: models.AccountStatusActive, InitialDeposit: test.initialDeposit,
				Currency: "SGD", BranchID: "SG-001",
			}

			err = NewAccountRepository(db).CreateAccount(context.Background(), input)
			if test.wantError {
				assert.Error(t, err)
				if test.wantErrorIs != nil {
					assert.ErrorIs(t, err, test.wantErrorIs)
				}
				return
			}
			require.NoError(t, err)
			var owner, accountType, status, currency, branchID string
			var deposit float64
			var openedOnSingaporeDate bool
			err = db.QueryRow(`SELECT owner_agent_id, account_type, status, initial_deposit, currency, branch_id,
				opening_date = (now() AT TIME ZONE 'Asia/Singapore')::date
				FROM accounts WHERE client_id = $1 AND deleted_at IS NULL`, clientID.String()).Scan(&owner, &accountType, &status, &deposit, &currency, &branchID, &openedOnSingaporeDate)
			require.NoError(t, err)
			assert.True(t, openedOnSingaporeDate, "opening_date should be today's Singapore date")
			assert.Equal(t, "agent-sub-901", owner)
			assert.Equal(t, "checking", accountType)
			assert.Equal(t, "active", status)
			assert.Equal(t, test.initialDeposit, deposit)
			assert.Equal(t, "SGD", currency)
			assert.Equal(t, "SG-001", branchID)
		})
	}
}
