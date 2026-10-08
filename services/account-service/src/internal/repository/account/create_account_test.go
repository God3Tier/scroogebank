package account

import (
	"account-service/src/internal/models"
	"context"
	"testing"
	"uuid"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestCreateAccount(t *testing.T) {
	tests := map[string]struct {
		fixture        string
		initialDeposit float64
		wantError      bool
	}{
		"persists a new account":             {fixture: "account_client.sql", initialDeposit: 250.75},
		"rejects a negative initial deposit": {fixture: "account_client.sql", initialDeposit: -1, wantError: true},
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
				return
			}
			require.NoError(t, err)
			var owner, accountType, status, currency, branchID string
			var deposit float64
			err = db.QueryRow(`SELECT owner_agent_id, account_type, status, initial_deposit, currency, branch_id FROM accounts WHERE client_id = $1`, clientID.String()).Scan(&owner, &accountType, &status, &deposit, &currency, &branchID)
			require.NoError(t, err)
			assert.Equal(t, "agent-sub-901", owner)
			assert.Equal(t, "checking", accountType)
			assert.Equal(t, "active", status)
			assert.Equal(t, test.initialDeposit, deposit)
			assert.Equal(t, "SGD", currency)
			assert.Equal(t, "SG-001", branchID)
		})
	}
}
