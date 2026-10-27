package client

import (
	"context"
	"fmt"
	"testing"

	accountsmock "client-profile-management-service/src/internal/accounts"
	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	repomock "client-profile-management-service/src/internal/repository/client"
	"client-profile-management-service/src/internal/users"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

const (
	reassignAuthorization = "Bearer admin-token"
	fromUserID            = "00000000-0000-0000-0000-000000000501"
	toUserID              = "00000000-0000-0000-0000-000000000502"
	reassignClientID      = "00000000-0000-0000-0000-000000000961"
)

var (
	fromAgent = users.User{Subject: "agent-from", Role: "AGENT", Enabled: false}
	toAgent   = users.User{Subject: "agent-to", Role: "AGENT", Enabled: true}
)

func TestReassignClientsMovesEveryClientOfTheUserAndTheirAccounts(t *testing.T) {
	ctx := context.Background()
	directory := users.NewMockDirectory(t)
	directory.On("Lookup", ctx, toUserID, reassignAuthorization).Return(toAgent, nil).Once()
	directory.On("Lookup", ctx, fromUserID, reassignAuthorization).Return(fromAgent, nil).Once()
	accounts := accountsmock.NewMockDirectory(t)
	accounts.On("SetOwner", ctx, []string{reassignClientID}, "agent-to", reassignAuthorization).Return(nil).Once()
	repo := repomock.NewMockRepository(t)
	moved := []models.Reassignment{{ClientID: reassignClientID, FromAgentID: "agent-from", ToAgentID: "agent-to"}}
	repo.On("ReassignClients", ctx, "agent-from", []string(nil), "agent-to", mock.Anything).
		Run(func(args mock.Arguments) {
			// The repository moves the accounts while it holds the clients.
			move := args.Get(4).(func(context.Context, []string) error)
			require.NoError(t, move(ctx, []string{reassignClientID}))
		}).
		Return(moved, nil).Once()

	got, err := NewClientService(repo, accounts, directory).ReassignClients(ctx,
		models.ReassignmentRequest{ToUserID: toUserID, FromUserID: fromUserID}, reassignAuthorization)

	require.NoError(t, err)
	assert.Equal(t, moved, got)
}

func TestReassignClientsMovesChosenClients(t *testing.T) {
	ctx := context.Background()
	directory := users.NewMockDirectory(t)
	directory.On("Lookup", ctx, toUserID, reassignAuthorization).Return(toAgent, nil).Once()
	repo := repomock.NewMockRepository(t)
	repo.On("ReassignClients", ctx, "", []string{reassignClientID}, "agent-to", mock.Anything).Return([]models.Reassignment{}, nil).Once()

	_, err := NewClientService(repo, nil, directory).ReassignClients(ctx,
		models.ReassignmentRequest{ToUserID: toUserID, ClientIDs: []string{" " + reassignClientID}}, reassignAuthorization)

	require.NoError(t, err)
}

func TestReassignClientsRefusesAReceiverThatIsNotAnEnabledAgent(t *testing.T) {
	ctx := context.Background()
	receivers := map[string]users.User{
		"an admin":          {Subject: "admin-sub", Role: "ADMIN", Enabled: true},
		"a disabled agent":  {Subject: "agent-to", Role: "AGENT"},
		"the agent already": {Subject: "agent-from", Role: "AGENT", Enabled: true},
	}
	for name, receiver := range receivers {
		t.Run(name, func(t *testing.T) {
			directory := users.NewMockDirectory(t)
			directory.On("Lookup", ctx, toUserID, reassignAuthorization).Return(receiver, nil).Once()
			directory.On("Lookup", ctx, fromUserID, reassignAuthorization).Return(fromAgent, nil).Maybe()

			_, err := NewClientService(repomock.NewMockRepository(t), nil, directory).ReassignClients(ctx,
				models.ReassignmentRequest{ToUserID: toUserID, FromUserID: fromUserID}, reassignAuthorization)

			assert.ErrorIs(t, err, clienterrors.ErrInvalidReceiver)
		})
	}
}

func TestReassignClientsMovesNothingWhenAUserCannotBeFound(t *testing.T) {
	ctx := context.Background()
	for name, lookupErr := range map[string]error{
		"a missing user":               clienterrors.ErrUserNotFound,
		"user-service does not answer": clienterrors.ErrUserServiceUnavailable,
	} {
		t.Run(name, func(t *testing.T) {
			directory := users.NewMockDirectory(t)
			directory.On("Lookup", ctx, toUserID, reassignAuthorization).Return(users.User{}, lookupErr).Once()

			_, err := NewClientService(repomock.NewMockRepository(t), nil, directory).ReassignClients(ctx,
				models.ReassignmentRequest{ToUserID: toUserID, FromUserID: fromUserID}, reassignAuthorization)

			assert.ErrorIs(t, err, lookupErr)
		})
	}
}

func TestReassignClientsChecksTheRequestFirst(t *testing.T) {
	tooMany := make([]string, models.MaxReassignClientIDs+1)
	for i := range tooMany {
		tooMany[i] = fmt.Sprintf("00000000-0000-0000-0000-%012d", i)
	}
	tests := map[string]struct {
		request models.ReassignmentRequest
		wantErr error
	}{
		"no receiver":            {request: models.ReassignmentRequest{FromUserID: fromUserID}, wantErr: clienterrors.ErrBadInput},
		"a receiver not a UUID":  {request: models.ReassignmentRequest{ToUserID: "agent", FromUserID: fromUserID}, wantErr: clienterrors.ErrBadInput},
		"neither selection":      {request: models.ReassignmentRequest{ToUserID: toUserID}, wantErr: clienterrors.ErrBadInput},
		"both selections":        {request: models.ReassignmentRequest{ToUserID: toUserID, FromUserID: fromUserID, ClientIDs: []string{reassignClientID}}, wantErr: clienterrors.ErrBadInput},
		"the same user twice":    {request: models.ReassignmentRequest{ToUserID: toUserID, FromUserID: toUserID}, wantErr: clienterrors.ErrInvalidReceiver},
		"a client not a UUID":    {request: models.ReassignmentRequest{ToUserID: toUserID, ClientIDs: []string{"client-1"}}, wantErr: clienterrors.ErrBadInput},
		"a client twice":         {request: models.ReassignmentRequest{ToUserID: toUserID, ClientIDs: []string{reassignClientID, reassignClientID}}, wantErr: clienterrors.ErrBadInput},
		"too many client IDs":    {request: models.ReassignmentRequest{ToUserID: toUserID, ClientIDs: tooMany}, wantErr: clienterrors.ErrBadInput},
		"a from user not a UUID": {request: models.ReassignmentRequest{ToUserID: toUserID, FromUserID: "agent"}, wantErr: clienterrors.ErrBadInput},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			// Neither user-service nor the repository is asked.
			_, err := NewClientService(repomock.NewMockRepository(t), nil, users.NewMockDirectory(t)).
				ReassignClients(context.Background(), test.request, reassignAuthorization)

			assert.ErrorIs(t, err, test.wantErr)
		})
	}
}
