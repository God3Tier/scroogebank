package account

import (
	"context"
	"errors"
	"testing"

	repomock "client-profile-management-service/src/internal/repository/account"
	orm "client-profile-management-service/src/internal/repository/orm"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestGetAccount(t *testing.T) {
	const accountID = "00000000-0000-0000-0000-000000000202"
	account := &orm.Account{ID: accountID}
	tests := []struct {
		name   string
		result *orm.Account
		err    error
	}{
		{name: "returns account from repository", result: account},
		{name: "returns repository error", err: errors.New("database unavailable")},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			repo.On("GetAccount", mock.Anything, accountID).Return(test.result, test.err).Once()

			got, err := NewAccountService(repo).GetAccount(context.Background(), accountID)

			assert.Equal(t, test.result, got)
			if test.err != nil {
				assert.ErrorIs(t, err, test.err)
			} else {
				assert.NoError(t, err)
			}
		})
	}
}
