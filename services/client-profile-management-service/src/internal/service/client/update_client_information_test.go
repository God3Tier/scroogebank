package client

import (
	"context"
	"errors"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	repomock "client-profile-management-service/src/internal/repository/client"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestUpdateClientInformation(t *testing.T) {
	ctx := context.Background()
	repositoryErr := errors.New("database unavailable")
	tests := map[string]struct {
		client      models.Client
		repoErr     error
		wantErr     error
		expectWrite bool
	}{
		"updates profile":                       {client: validClient(), expectWrite: true},
		"rejects missing required profile data": {client: models.Client{}, wantErr: clienterrors.ErrBadInput},
		"returns repository error":              {client: validClient(), repoErr: repositoryErr, wantErr: repositoryErr, expectWrite: true},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			if test.expectWrite {
				repo.On("UpdateClientInformation", ctx, test.client.ClientID.String(), mock.Anything).Return(test.repoErr).Once()
			}
			err := NewClientService(repo, nil, nil).UpdateClientInformation(ctx, test.client)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
		})
	}
}
