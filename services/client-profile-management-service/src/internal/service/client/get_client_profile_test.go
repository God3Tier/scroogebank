package client

import (
	"context"
	"errors"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	repomock "client-profile-management-service/src/internal/repository/client"

	"github.com/stretchr/testify/assert"
)

func TestGetClientProfile(t *testing.T) {
	ctx := context.Background()
	repositoryErr := errors.New("database unavailable")
	wantClient := validClient()
	tests := map[string]struct {
		clientID string
		result   models.Client
		repoErr  error
		wantErr  error
		callRepo bool
	}{
		"returns profile":          {clientID: wantClient.ClientID.String(), result: wantClient, callRepo: true},
		"rejects blank ID":         {clientID: " ", wantErr: clienterrors.ErrBadInput},
		"returns repository error": {clientID: "client-123", repoErr: repositoryErr, wantErr: repositoryErr, callRepo: true},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			if test.callRepo {
				repo.On("GetClientProfile", ctx, test.clientID).Return(test.result, test.repoErr).Once()
			}
			got, err := NewClientService(repo).GetClientProfile(ctx, test.clientID)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
			assert.Equal(t, test.result, got)
		})
	}
}
