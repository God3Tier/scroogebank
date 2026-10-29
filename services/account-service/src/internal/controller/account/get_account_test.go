package account

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"account-service/src/internal/contract"
	clienterrors "account-service/src/internal/errors"
	orm "account-service/src/internal/repository/orm"
	servicemock "account-service/src/internal/service/account"

	"github.com/aarondl/sqlboiler/v4/types"
	"github.com/ericlagergren/decimal"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

// storedAccount is an account as the database returns it, so responses carry
// every field the contract describes.
func storedAccount(id string) *orm.Account {
	deposit, _ := new(decimal.Big).SetString("1000.00")
	opened := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC)
	return &orm.Account{
		ID: id, ClientID: "00000000-0000-0000-0000-000000000201", OwnerAgentID: "agent-sub",
		AccountType: "savings", Status: "active", OpeningDate: opened,
		InitialDeposit: types.NewDecimal(deposit), Currency: "SGD", BranchID: "SG-001",
		CreatedAt: opened, UpdatedAt: opened,
	}
}

func TestGetAccount(t *testing.T) {
	const id = "00000000-0000-0000-0000-000000000202"
	tests := []struct {
		name       string
		query      string
		account    *orm.Account
		serviceErr error
		wantStatus int
		called     bool
	}{
		{name: "returns existing account", query: "?id=" + id, account: storedAccount(id), wantStatus: http.StatusOK, called: true},
		{name: "rejects missing ID", wantStatus: http.StatusBadRequest},
		{name: "maps missing account", query: "?id=" + id, serviceErr: clienterrors.ErrNotFound, wantStatus: http.StatusNotFound, called: true},
		{name: "maps unexpected service error", query: "?id=" + id, serviceErr: errors.New("database unavailable"), wantStatus: http.StatusInternalServerError, called: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			svc := servicemock.NewMockAccountService(t)
			if test.called {
				svc.On("GetAccount", mock.Anything, id).Return(test.account, test.serviceErr).Once()
			}
			request := httptest.NewRequest(http.MethodGet, "/api/accounts"+test.query, nil)
			response := httptest.NewRecorder()
			NewAccountController(svc).GetAccount(response, request)
			contract.Check(t, "accounts-api.yaml", http.MethodGet, "/api/accounts/{accountId}", response)
			assert.Equal(t, test.wantStatus, response.Code)
		})
	}
}
