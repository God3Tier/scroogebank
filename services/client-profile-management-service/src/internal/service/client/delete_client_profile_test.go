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
	const clientID = "7f3c1e2a-1b4d-4c8e-9f60-2a5b7c9d0e11"
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
		"deletes a client with no accounts":  {clientID: clientID, callAccounts: true, callRepo: true},
		"rejects blank ID":                   {clientID: "  ", wantErr: clienterrors.ErrBadInput},
		"treats a malformed ID as no client": {clientID: "not-a-uuid", wantErr: clienterrors.ErrClientNotFound},
		"returns repository error": {
			clientID: clientID, repoErr: repositoryErr, wantErr: repositoryErr, callAccounts: true, callRepo: true,
		},
		"refuses a client that still has accounts": {
			clientID: clientID, hasAccounts: true, wantErr: clienterrors.ErrClientHasAccounts, callAccounts: true,
		},
		"fails closed when the account service cannot answer": {
			clientID: clientID, lookupErr: clienterrors.ErrAccountServiceUnavailable,
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
