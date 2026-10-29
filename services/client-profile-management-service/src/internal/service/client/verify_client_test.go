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
	const (
		first  = "0199f1d2-0000-7000-8000-000000000001"
		second = "0199f1d2-0000-7000-8000-000000000002"
	)

	tests := map[string]struct {
		clientID    string
		agentID     string
		documentIDs []string
		repoIDs     []string
		repoErr     error
		wantErr     error
	}{
		"verifies with the named documents": {clientID: "client-123", agentID: "agent-1", documentIDs: []string{first, second}, repoIDs: []string{first, second}},
		"drops repeated and differently written IDs": {clientID: "client-123", agentID: "agent-1",
			documentIDs: []string{first, " 0199F1D2-0000-7000-8000-000000000001 ", second}, repoIDs: []string{first, second}},
		"returns repository error":    {clientID: "client-123", agentID: "agent-1", documentIDs: []string{first}, repoIDs: []string{first}, repoErr: repositoryErr, wantErr: repositoryErr},
		"rejects blank ID":            {clientID: "  ", agentID: "agent-1", documentIDs: []string{first}, wantErr: clienterrors.ErrBadInput},
		"rejects an unknown agent":    {clientID: "client-123", documentIDs: []string{first}, wantErr: clienterrors.ErrBadInput},
		"verifies without a document": {clientID: "client-123", agentID: "agent-1", repoIDs: []string{}},
		"rejects IDs that are not document IDs": {clientID: "client-123", agentID: "agent-1",
			documentIDs: []string{first, "Alice Tan"}, wantErr: clienterrors.ErrBadInput},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			if test.repoIDs != nil {
				repo.On("VerifyClientIdentity", ctx, test.clientID, test.agentID, test.repoIDs).Return(test.repoErr).Once()
			}
			err := NewClientService(repo, nil, nil).VerifyClientIdentity(ctx, test.clientID, test.agentID, test.documentIDs)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
		})
	}
}
