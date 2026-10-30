package client

import (
	"context"
	"errors"
	"testing"

	accountsmock "client-profile-management-service/src/internal/accounts"
	clienterrors "client-profile-management-service/src/internal/errors"
	repomock "client-profile-management-service/src/internal/repository/client"

	"github.com/stretchr/testify/assert"
)

func TestDeleteClientProfile(t *testing.T) {
	ctx := context.Background()
	const authorization = "Bearer caller-token"
	repositoryErr := errors.New("database unavailable")
	tests := map[string]struct {
		clientID     string
		hasAccounts  bool
		lookupErr    error
		repoErr      error
		wantErr      error
		callAccounts bool
		callRepo     bool
	}{
		"deletes a client with no accounts": {clientID: "client-123", callAccounts: true, callRepo: true},
		"rejects blank ID":                  {clientID: "  ", wantErr: clienterrors.ErrBadInput},
		"returns repository error": {
			clientID: "client-123", repoErr: repositoryErr, wantErr: repositoryErr, callAccounts: true, callRepo: true,
		},
		"refuses a client that still has accounts": {
			clientID: "client-123", hasAccounts: true, wantErr: clienterrors.ErrClientHasAccounts, callAccounts: true,
		},
		"fails closed when the account service cannot answer": {
			clientID: "client-123", lookupErr: clienterrors.ErrAccountServiceUnavailable,
			wantErr: clienterrors.ErrAccountServiceUnavailable, callAccounts: true,
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			directory := accountsmock.NewMockDirectory(t)
			if test.callAccounts {
				directory.On("HasAccounts", ctx, test.clientID, authorization).Return(test.hasAccounts, test.lookupErr).Once()
			}
			repo := repomock.NewMockRepository(t)
			if test.callRepo {
				repo.On("DeleteClient", ctx, test.clientID).Return(test.repoErr).Once()
			}
			err := NewClientService(repo, directory, nil).DeleteClientProfile(ctx, test.clientID, authorization)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
		})
	}
}
