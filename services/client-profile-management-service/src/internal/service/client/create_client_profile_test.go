package client

import (
	"context"
	"errors"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	repomock "client-profile-management-service/src/internal/repository/client"
	"uuid"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestCreateClientProfile(t *testing.T) {
	ctx := context.Background()
	databaseErr := errors.New("database unavailable")

	tests := map[string]struct {
		client      models.Client
		repoErr     error
		wantErr     error
		expectWrite bool
	}{
		"creates profile with generated ID": {
			client:      validClient(),
			expectWrite: true,
		},
		"rejects missing required field": {
			client:  models.Client{},
			wantErr: clienterrors.ErrBadInput,
		},
		"returns repository error": {
			client:      validClient(),
			repoErr:     databaseErr,
			wantErr:     databaseErr,
			expectWrite: true,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			if test.expectWrite {
				repo.On("CreateClient", ctx, mock.MatchedBy(func(got models.Client) bool {
					return got.ClientID != uuid.Nil() && got.FirstName == test.client.FirstName
				})).Return(test.repoErr).Once()
			}

			err := NewClientService(repo).CreateClientProfile(ctx, test.client)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
		})
	}
}
