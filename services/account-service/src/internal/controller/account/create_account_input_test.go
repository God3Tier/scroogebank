package account

import (
	"testing"
	"uuid"

	clienterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"

	"github.com/stretchr/testify/assert"
)

func TestValidateAccountAndParse(t *testing.T) {
	tests := map[string]struct {
		input   CreateAccountInput
		wantErr error
	}{
		"parses valid account and normalizes values": {
			input: CreateAccountInput{
				ClientID:       uuid.New(),
				Type:           " savings ",
				Status:         " active ",
				InitialDeposit: 100,
				Currency:       " sgd ",
				BranchID:       "SG-001",
			},
		},
		"rejects missing client ID": {
			input:   CreateAccountInput{Type: "SAVINGS", Status: "ACTIVE", Currency: "SGD", BranchID: "SG-001"},
			wantErr: clienterrors.ErrBadInput,
		},
		"rejects unsupported account type": {
			input:   CreateAccountInput{ClientID: uuid.New(), Type: "OTHER", Status: "ACTIVE", Currency: "SGD", BranchID: "SG-001"},
			wantErr: clienterrors.ErrBadInput,
		},
		"rejects unsupported status": {
			input:   CreateAccountInput{ClientID: uuid.New(), Type: "SAVINGS", Status: "OTHER", Currency: "SGD", BranchID: "SG-001"},
			wantErr: clienterrors.ErrBadInput,
		},
		"rejects negative initial deposit": {
			input:   CreateAccountInput{ClientID: uuid.New(), Type: "SAVINGS", Status: "ACTIVE", InitialDeposit: -1, Currency: "SGD", BranchID: "SG-001"},
			wantErr: clienterrors.ErrBadInput,
		},
		"rejects empty currency": {
			input:   CreateAccountInput{ClientID: uuid.New(), Type: "SAVINGS", Status: "ACTIVE", Currency: " ", BranchID: "SG-001"},
			wantErr: clienterrors.ErrBadInput,
		},
		"rejects currency with wrong length": {
			input:   CreateAccountInput{ClientID: uuid.New(), Type: "SAVINGS", Status: "ACTIVE", Currency: "SG", BranchID: "SG-001"},
			wantErr: clienterrors.ErrBadInput,
		},
		"rejects missing branch ID": {
			input:   CreateAccountInput{ClientID: uuid.New(), Type: "SAVINGS", Status: "ACTIVE", Currency: "SGD"},
			wantErr: clienterrors.ErrBadInput,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			got, err := test.input.ValidateAccountAndParse()
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
			assert.Equal(t, test.input.ClientID, got.ClientID)
			assert.Equal(t, models.AccountTypeSavings, got.Type)
			assert.Equal(t, models.AccountStatusActive, got.Status)
			assert.Equal(t, "SGD", got.Currency)
			assert.Equal(t, test.input.InitialDeposit, got.InitialDeposit)
			assert.Equal(t, test.input.BranchID, got.BranchID)
		})
	}
}
