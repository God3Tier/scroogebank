package account

import (
	"context"
	"errors"
	"testing"
	"uuid"

	clientsmock "account-service/src/internal/clients"
	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	repomock "account-service/src/internal/repository/account"

	"github.com/stretchr/testify/assert"
)

func TestCreateAccount(t *testing.T) {
	ctx := context.Background()
	const authorization = "Bearer caller-token"
	databaseErr := errors.New("database unavailable")
	input := models.CreateAccountInput{
		ClientID:       uuid.New(),
		Type:           models.AccountType("SAVINGS"),
		Status:         models.AccountStatus("ACTIVE"),
		InitialDeposit: 100,
		Currency:       "SGD",
		BranchID:       "SG-001",
	}
	stored := input
	stored.OwnerAgentID = "client-owner-sub"

	tests := map[string]struct {
		lookupErr error
		repoErr   error
		wantErr   error
		callRepo  bool
	}{
		"stores the client's owner, not the caller": {callRepo: true},
		"returns repository error":                  {repoErr: databaseErr, wantErr: databaseErr, callRepo: true},
		"refuses a missing or foreign client":       {lookupErr: accounterrors.ErrClientNotFound, wantErr: accounterrors.ErrClientNotFound},
		"fails closed when the client service is down": {
			lookupErr: accounterrors.ErrClientServiceUnavailable, wantErr: accounterrors.ErrClientServiceUnavailable,
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			directory := clientsmock.NewMockDirectory(t)
			owner := ""
			if test.lookupErr == nil {
				owner = "client-owner-sub"
			}
			directory.On("OwnerOf", ctx, input.ClientID.String(), authorization).Return(owner, test.lookupErr).Once()
			repo := repomock.NewMockRepository(t)
			if test.callRepo {
				repo.On("CreateAccount", ctx, stored).Return(test.repoErr).Once()
			}

			err := NewAccountService(repo, directory).CreateAccount(ctx, input, authorization)

			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
		})
	}
}
