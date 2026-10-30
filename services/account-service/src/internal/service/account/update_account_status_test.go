package account

import (
	"context"
	"testing"

	"account-service/src/internal/clients"
	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	repomock "account-service/src/internal/repository/account"
	orm "account-service/src/internal/repository/orm"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestUpdateAccountStatus(t *testing.T) {
	ctx := context.Background()
	const (
		authorization = "Bearer caller-token"
		accountID     = "00000000-0000-0000-0000-000000000202"
		clientID      = "00000000-0000-0000-0000-000000000201"
	)
	agent := models.Caller{Subject: "agent-sub"}
	admin := models.Caller{Subject: "admin-sub", Admin: true}
	account := &orm.Account{ID: accountID, ClientID: clientID, Status: "pending"}

	tests := map[string]struct {
		caller     models.Caller
		getErr     error
		client     clients.Client
		lookupErr  error
		callLookup bool
		callUpdate bool
		wantErr    error
	}{
		"changes a verified client's account": {
			caller: agent, client: clients.Client{OwnerAgentID: "agent-sub", Verified: true}, callLookup: true, callUpdate: true,
		},
		// Admins do not manage client accounts (UM18).
		"refuses an admin": {caller: admin, wantErr: accounterrors.ErrForbidden},
		"returns not found for a missing or deleted account": {
			caller: agent, getErr: accounterrors.ErrNotFound, wantErr: accounterrors.ErrNotFound,
		},
		"refuses an unverified client's account": {
			caller: agent, client: clients.Client{OwnerAgentID: "agent-sub"}, callLookup: true, wantErr: accounterrors.ErrClientNotVerified,
		},
		"fails closed when the client service is down": {
			caller: agent, lookupErr: accounterrors.ErrClientServiceUnavailable, callLookup: true, wantErr: accounterrors.ErrClientServiceUnavailable,
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			directory := clients.NewMockDirectory(t)
			if !test.caller.Admin {
				if test.getErr != nil {
					repo.On("GetAccount", ctx, accountID).Return(nil, test.getErr).Once()
				} else {
					repo.On("GetAccount", ctx, accountID).Return(account, nil).Once()
				}
			}
			if test.callLookup {
				directory.On("Lookup", ctx, clientID, authorization).Return(test.client, test.lookupErr).Once()
			}
			if test.callUpdate {
				repo.On("UpdateAccountStatus", ctx, accountID, "ACTIVE").Return("pending", nil).Once()
			}

			before, after, err := NewAccountService(repo, directory).UpdateAccountStatus(ctx, accountID, models.AccountStatusActive, test.caller, authorization)

			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)
			assert.Equal(t, models.AccountStatusPending, before)
			assert.Equal(t, models.AccountStatusActive, after)
		})
	}
}
