package account

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	clienterrors "account-service/src/internal/errors"
	servicemock "account-service/src/internal/service/account"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestDeleteAccount(t *testing.T) {
	const id = "account-123"
	tests := map[string]struct {
		query      string
		serviceErr error
		wantStatus int
		callSvc    bool
	}{
		"deletes account":               {query: "id=" + id, wantStatus: http.StatusOK, callSvc: true},
		"rejects missing ID":            {wantStatus: http.StatusBadRequest},
		"maps invalid account":          {query: "id=" + id, serviceErr: clienterrors.ErrBadInput, wantStatus: http.StatusBadRequest, callSvc: true},
		"maps missing account":          {query: "id=" + id, serviceErr: clienterrors.ErrNotFound, wantStatus: http.StatusNotFound, callSvc: true},
		"maps unexpected service error": {query: "id=" + id, serviceErr: errors.New("database unavailable"), wantStatus: http.StatusInternalServerError, callSvc: true},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockAccountService(t)
			if test.callSvc {
				svc.On("DeleteAccount", mock.Anything, id).Return(test.serviceErr).Once()
			}
			req := httptest.NewRequest(http.MethodDelete, "/api/accounts?"+test.query, nil)
			res := httptest.NewRecorder()
			NewAccountController(svc).DeleteAccount(res, req)
			assert.Equal(t, test.wantStatus, res.Code)
		})
	}
}
