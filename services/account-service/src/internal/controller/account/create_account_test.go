package account

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"uuid"

	clienterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	servicemock "account-service/src/internal/service/account"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestCreateAccount(t *testing.T) {
	const id = "123e4567-e89b-12d3-a456-426614174000"
	clientID, err := uuid.Parse(id)
	if err != nil {
		t.Fatal(err)
	}
	const body = `{"client_id":"` + id + `","account_type":"savings","status":"active","opening_date":"2024-01-01T00:00:00Z","initial_deposit":100,"currency":"sgd","branch_id":"SG-001"}`
	wantInput := models.CreateAccountInput{
		ClientID: clientID, Type: models.AccountTypeSavings, Status: models.AccountStatusActive,
		InitialDeposit: 100, Currency: "SGD", BranchID: "SG-001",
	}
	tests := map[string]struct {
		body       string
		serviceErr error
		wantStatus int
		callSvc    bool
	}{
		"creates account":               {body: body, wantStatus: http.StatusCreated, callSvc: true},
		"rejects malformed JSON":        {body: `{`, wantStatus: http.StatusBadRequest},
		"rejects invalid fields":        {body: `{"account_type":"bad"}`, wantStatus: http.StatusBadRequest},
		"maps invalid account":          {body: body, serviceErr: clienterrors.ErrBadInput, wantStatus: http.StatusBadRequest, callSvc: true},
		"maps duplicate account":        {body: body, serviceErr: clienterrors.ErrDuplicateAccount, wantStatus: http.StatusConflict, callSvc: true},
		"maps missing client":           {body: body, serviceErr: clienterrors.ErrClientNotFound, wantStatus: http.StatusNotFound, callSvc: true},
		"maps client service outage":    {body: body, serviceErr: clienterrors.ErrClientServiceUnavailable, wantStatus: http.StatusServiceUnavailable, callSvc: true},
		"maps unexpected service error": {body: body, serviceErr: errors.New("database unavailable"), wantStatus: http.StatusInternalServerError, callSvc: true},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockAccountService(t)
			if test.callSvc {
				svc.On("CreateAccount", mock.Anything, mock.MatchedBy(func(got models.CreateAccountInput) bool {
					// The owner is never taken from the request body.
					return got.AccountID != uuid.Nil() && got.ClientID == wantInput.ClientID &&
						got.OwnerAgentID == "" &&
						got.Type == wantInput.Type && got.Status == wantInput.Status &&
						got.InitialDeposit == wantInput.InitialDeposit && got.Currency == wantInput.Currency &&
						got.BranchID == wantInput.BranchID
				}), "Bearer caller-token").Return(test.serviceErr).Once()
			}
			req := httptest.NewRequest(http.MethodPost, "/api/accounts", strings.NewReader(test.body))
			req.Header.Set("Authorization", "Bearer caller-token")
			res := httptest.NewRecorder()
			NewAccountController(svc).CreateAccount(res, req)
			assert.Equal(t, test.wantStatus, res.Code)
		})
	}
}
