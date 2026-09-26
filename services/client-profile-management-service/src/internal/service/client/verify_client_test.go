package client

import (
	"context"
	"errors"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"
	repomock "client-profile-management-service/src/internal/repository/client"

	"github.com/stretchr/testify/assert"
)

func TestVerifyClientIdentity(t *testing.T) {
	ctx := context.Background()
	repositoryErr := errors.New("verification unavailable")

	tests := map[string]struct {
		clientID string
		repoErr  error
		wantErr  error
		callRepo bool
	}{
		"verifies client":          {clientID: "client-123", callRepo: true},
		"rejects blank ID":         {clientID: "  ", wantErr: clienterrors.ErrBadInput},
		"returns repository error": {clientID: "client-123", repoErr: repositoryErr, wantErr: repositoryErr, callRepo: true},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			if test.callRepo {
				repo.On("VerifyClientIdentity", ctx, test.clientID).Return(test.repoErr).Once()
			}
			err := NewClientService(repo).VerifyClientIdentity(ctx, test.clientID)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
		})
	}
}
