package account

import (
	"context"
	"errors"
	"testing"

	clienterrors "account-service/src/internal/errors"
	repomock "account-service/src/internal/repository/account"

	"github.com/stretchr/testify/assert"
)

func TestDeleteAccount(t *testing.T) {
	ctx := context.Background()
	databaseErr := errors.New("database unavailable")
	tests := map[string]struct {
		accountID string
		repoErr   error
		wantErr   error
		callRepo  bool
	}{
		"deletes account":          {accountID: "account-123", callRepo: true},
		"rejects blank ID":         {accountID: " ", wantErr: clienterrors.ErrBadInput},
		"returns repository error": {accountID: "account-123", repoErr: databaseErr, wantErr: databaseErr, callRepo: true},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			if test.callRepo {
				repo.On("DeleteAccount", ctx, test.accountID).Return(test.repoErr).Once()
			}
			err := NewAccountService(repo, nil).DeleteAccount(ctx, test.accountID)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
		})
	}
}
