package account

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"account-service/src/internal/audit"
	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	servicemock "account-service/src/internal/service/account"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

func TestUpdateAccountStatus(t *testing.T) {
	const accountID = "00000000-0000-0000-0000-000000000202"
	caller := models.Caller{Subject: "agent-sub"}
	tests := map[string]struct {
		body       string
		serviceErr error
		wantStatus int
		callSvc    bool
	}{
		"changes the status":                 {body: `{"status":"active"}`, wantStatus: http.StatusOK, callSvc: true},
		"rejects malformed JSON":             {body: `{`, wantStatus: http.StatusBadRequest},
		"rejects an unknown status":          {body: `{"status":"frozen"}`, wantStatus: http.StatusBadRequest},
		"refuses an admin":                   {body: `{"status":"active"}`, serviceErr: accounterrors.ErrForbidden, wantStatus: http.StatusForbidden, callSvc: true},
		"refuses an unverified client":       {body: `{"status":"active"}`, serviceErr: accounterrors.ErrClientNotVerified, wantStatus: http.StatusForbidden, callSvc: true},
		"maps a missing account":             {body: `{"status":"active"}`, serviceErr: accounterrors.ErrNotFound, wantStatus: http.StatusNotFound, callSvc: true},
		"maps a client the caller can't see": {body: `{"status":"active"}`, serviceErr: accounterrors.ErrClientNotFound, wantStatus: http.StatusNotFound, callSvc: true},
		"maps a client service outage":       {body: `{"status":"active"}`, serviceErr: accounterrors.ErrClientServiceUnavailable, wantStatus: http.StatusServiceUnavailable, callSvc: true},
		"maps an unexpected error":           {body: `{"status":"active"}`, serviceErr: errors.New("database unavailable"), wantStatus: http.StatusInternalServerError, callSvc: true},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockAccountService(t)
			if test.callSvc {
				before, after := models.AccountStatusPending, models.AccountStatusActive
				if test.serviceErr != nil {
					before, after = "", ""
				}
				svc.On("UpdateAccountStatus", mock.Anything, accountID, models.AccountStatusActive, caller, "Bearer caller-token").
					Return(before, after, test.serviceErr).Once()
			}
			req := httptest.NewRequest(http.MethodPatch, "/api/accounts/"+accountID, strings.NewReader(test.body))
			req.Header.Set("Authorization", "Bearer caller-token")
			ctx, change := audit.WithChange(req.Context())
			res := httptest.NewRecorder()

			NewAccountController(svc).UpdateAccountStatus(res, req.WithContext(ctx), caller, accountID)

			assert.Equal(t, test.wantStatus, res.Code)
			if test.wantStatus != http.StatusOK {
				assert.Equal(t, audit.Change{}, *change, "a refused change records nothing for the audit")
				return
			}
			assert.Equal(t, audit.Change{Attribute: "status", Before: "PENDING", After: "ACTIVE"}, *change)
			var body map[string]string
			require.NoError(t, json.Unmarshal(res.Body.Bytes(), &body))
			assert.Equal(t, map[string]string{"account_id": accountID, "status": "ACTIVE"}, body)
		})
	}
}
