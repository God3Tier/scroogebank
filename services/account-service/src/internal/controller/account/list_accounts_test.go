package account

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"account-service/src/internal/models"
	orm "account-service/src/internal/repository/orm"
	servicemock "account-service/src/internal/service/account"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestListAccounts(t *testing.T) {
	const clientID = "00000000-0000-0000-0000-000000000203"
	caller := models.Caller{Subject: "agent-sub"}

	t.Run("lists the caller's accounts for the client", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("ListAccounts", mock.Anything, clientID, caller).
			Return(orm.AccountSlice{{ID: "00000000-0000-0000-0000-000000000231"}}, nil).Once()
		req := httptest.NewRequest(http.MethodGet, "/api/accounts?clientId="+clientID, nil)
		res := httptest.NewRecorder()

		NewAccountController(svc).ListAccounts(res, req, caller)

		assert.Equal(t, http.StatusOK, res.Code)
		assert.Contains(t, res.Body.String(), "00000000-0000-0000-0000-000000000231")
	})

	t.Run("an empty result is an empty array", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("ListAccounts", mock.Anything, clientID, caller).Return(orm.AccountSlice{}, nil).Once()
		req := httptest.NewRequest(http.MethodGet, "/api/accounts?clientId="+clientID, nil)
		res := httptest.NewRecorder()

		NewAccountController(svc).ListAccounts(res, req, caller)

		assert.Equal(t, http.StatusOK, res.Code)
		assert.JSONEq(t, `[]`, res.Body.String())
	})

	t.Run("rejects a missing or malformed client ID without calling the service", func(t *testing.T) {
		for _, query := range []string{"", "?clientId=not-a-uuid"} {
			svc := servicemock.NewMockAccountService(t)
			req := httptest.NewRequest(http.MethodGet, "/api/accounts"+query, nil)
			res := httptest.NewRecorder()

			NewAccountController(svc).ListAccounts(res, req, caller)

			assert.Equal(t, http.StatusBadRequest, res.Code)
		}
	})

	t.Run("maps an unexpected service error", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("ListAccounts", mock.Anything, clientID, caller).Return(nil, errors.New("database unavailable")).Once()
		req := httptest.NewRequest(http.MethodGet, "/api/accounts?clientId="+clientID, nil)
		res := httptest.NewRecorder()

		NewAccountController(svc).ListAccounts(res, req, caller)

		assert.Equal(t, http.StatusInternalServerError, res.Code)
	})
}
