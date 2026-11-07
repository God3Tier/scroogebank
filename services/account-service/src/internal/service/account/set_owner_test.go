package account

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"

	accounterrors "account-service/src/internal/errors"
	repomock "account-service/src/internal/repository/account"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestSetOwner(t *testing.T) {
	ctx := context.Background()
	const clientID = "00000000-0000-0000-0000-000000000241"

	t.Run("passes each client once to the repository", func(t *testing.T) {
		repo := repomock.NewMockRepository(t)
		repo.On("SetOwner", ctx, []string{clientID}, "agent-new").Return(int64(2), nil).Once()

		updated, err := NewAccountService(repo, nil).SetOwner(ctx, []string{clientID, " " + clientID}, " agent-new ")

		require.NoError(t, err)
		assert.Equal(t, int64(2), updated)
	})

	t.Run("returns the repository's error", func(t *testing.T) {
		repo := repomock.NewMockRepository(t)
		databaseErr := errors.New("database unavailable")
		repo.On("SetOwner", ctx, []string{clientID}, "agent-new").Return(int64(0), databaseErr).Once()

		_, err := NewAccountService(repo, nil).SetOwner(ctx, []string{clientID}, "agent-new")

		assert.ErrorIs(t, err, databaseErr)
	})

	tooMany := make([]string, MaxOwnerClients+1)
	for i := range tooMany {
		tooMany[i] = fmt.Sprintf("00000000-0000-0000-0000-%012d", i)
	}
	refused := map[string]struct {
		clientIDs []string
		owner     string
	}{
		"no owner":            {clientIDs: []string{clientID}, owner: " "},
		"an owner too long":   {clientIDs: []string{clientID}, owner: strings.Repeat("a", 129)},
		"no clients":          {owner: "agent-new"},
		"too many clients":    {clientIDs: tooMany, owner: "agent-new"},
		"a client not a UUID": {clientIDs: []string{clientID, "client-1"}, owner: "agent-new"},
	}
	for name, test := range refused {
		t.Run("refuses "+name+" without a write", func(t *testing.T) {
			_, err := NewAccountService(repomock.NewMockRepository(t), nil).SetOwner(ctx, test.clientIDs, test.owner)

			assert.ErrorIs(t, err, accounterrors.ErrBadInput)
		})
	}
}
