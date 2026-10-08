package account

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"
	orm "client-profile-management-service/src/internal/repository/orm"
	servicemock "client-profile-management-service/src/internal/service/account"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

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
		{name: "returns existing account", query: "?id=" + id, account: &orm.Account{ID: id}, wantStatus: http.StatusOK, called: true},
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
			assert.Equal(t, test.wantStatus, response.Code)
		})
	}
}
