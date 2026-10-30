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

func TestListClients(t *testing.T) {
	ctx := context.Background()
	repositoryErr := errors.New("database unavailable")
	agent := models.Caller{Subject: "agent-sub"}
	admin := models.Caller{Subject: "admin-sub", Admin: true}
	clients := []models.Client{validClient()}
	tests := map[string]struct {
		caller     models.Caller
		page, size int
		wantOwner  string
		callRepo   bool
		total      int64
		repoErr    error
		wantErr    error
		wantPages  int
	}{
		"scopes an agent to their own clients": {caller: agent, page: 0, size: 20, wantOwner: "agent-sub", callRepo: true, total: 1, wantPages: 1},
		"lists every client for an admin":      {caller: admin, page: 0, size: 20, wantOwner: "", callRepo: true, total: 1, wantPages: 1},
		"counts partial last pages":            {caller: admin, page: 2, size: 20, wantOwner: "", callRepo: true, total: 41, wantPages: 3},
		"rejects a negative page":              {caller: agent, page: -1, size: 20, wantErr: clienterrors.ErrBadInput},
		"rejects a zero size":                  {caller: agent, page: 0, size: 0, wantErr: clienterrors.ErrBadInput},
		"rejects a size above the maximum":     {caller: agent, page: 0, size: MaxPageSize + 1, wantErr: clienterrors.ErrBadInput},
		"refuses an agent without a subject":   {caller: models.Caller{}, page: 0, size: 20, wantErr: clienterrors.ErrForbidden},
		"returns repository error":             {caller: agent, page: 0, size: 20, wantOwner: "agent-sub", callRepo: true, repoErr: repositoryErr, wantErr: repositoryErr},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			if test.callRepo {
				repo.On("ListClients", ctx, test.wantOwner, test.page, test.size).Return(clients, test.total, test.repoErr).Once()
			}
			got, err := NewClientService(repo, nil, nil).ListClients(ctx, test.caller, test.page, test.size)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
			assert.Equal(t, models.ClientPage{
				Content: clients, Page: test.page, Size: test.size, TotalElements: test.total, TotalPages: test.wantPages,
			}, got)
		})
	}
}
