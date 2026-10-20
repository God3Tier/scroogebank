package account

import (
	"context"
	"errors"
	"testing"
	"uuid"

	"account-service/src/internal/clients"
	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	repomock "account-service/src/internal/repository/account"

	"github.com/stretchr/testify/assert"
)

func TestCreateAccount(t *testing.T) {
	ctx := context.Background()
	const authorization = "Bearer caller-token"
	databaseErr := errors.New("database unavailable")
	agent := models.Caller{Subject: "agent-sub"}
	admin := models.Caller{Subject: "admin-sub", Admin: true}
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
	verified := clients.Client{OwnerAgentID: "client-owner-sub", Verified: true}
	unverified := clients.Client{OwnerAgentID: "client-owner-sub"}

	tests := map[string]struct {
		caller    models.Caller
		client    clients.Client
		lookupErr error
		repoErr   error
		wantErr   error
		callRepo  bool
	}{
		"stores the client's owner, not the caller": {caller: agent, client: verified, callRepo: true},
		"returns repository error":                  {caller: agent, client: verified, repoErr: databaseErr, wantErr: databaseErr, callRepo: true},
		"refuses a missing or foreign client":       {caller: agent, lookupErr: accounterrors.ErrClientNotFound, wantErr: accounterrors.ErrClientNotFound},
		"fails closed when the client service is down": {
			caller: agent, lookupErr: accounterrors.ErrClientServiceUnavailable, wantErr: accounterrors.ErrClientServiceUnavailable,
		},
		"refuses an agent an unverified client":           {caller: agent, client: unverified, wantErr: accounterrors.ErrClientNotVerified},
		"lets an admin open one for an unverified client": {caller: admin, client: unverified, callRepo: true},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			directory := clients.NewMockDirectory(t)
			directory.On("Lookup", ctx, input.ClientID.String(), authorization).Return(test.client, test.lookupErr).Once()
			repo := repomock.NewMockRepository(t)
			if test.callRepo {
				repo.On("CreateAccount", ctx, stored).Return(test.repoErr).Once()
			}

			err := NewAccountService(repo, directory).CreateAccount(ctx, input, test.caller, authorization)

			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
		})
	}
}
