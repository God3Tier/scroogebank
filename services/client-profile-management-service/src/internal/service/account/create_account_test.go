package account

import (
	"context"
	"errors"
	"testing"
	"uuid"

	"client-profile-management-service/src/internal/models"
	repomock "client-profile-management-service/src/internal/repository/account"

	"github.com/stretchr/testify/assert"
)

func TestCreateAccount(t *testing.T) {
	ctx := context.Background()
	databaseErr := errors.New("database unavailable")
	input := models.CreateAccountInput{
		ClientID:       uuid.New(),
		Type:           models.AccountType("SAVINGS"),
		Status:         models.AccountStatus("ACTIVE"),
		InitialDeposit: 100,
		Currency:       "SGD",
		BranchID:       "SG-001",
	}
	tests := map[string]struct {
		repoErr error
		wantErr error
	}{
		"creates account":          {},
		"returns repository error": {repoErr: databaseErr, wantErr: databaseErr},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			repo.On("CreateAccount", ctx, input).Return(test.repoErr).Once()
			err := NewAccountService(repo).CreateAccount(ctx, input)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
		})
	}
}
