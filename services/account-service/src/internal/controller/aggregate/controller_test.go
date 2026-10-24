package aggregate

import (
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"account-service/src/internal/contract"
	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	servicemock "account-service/src/internal/service/aggregate"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestAccountStats(t *testing.T) {
	caller := models.Caller{Subject: "agent-sub"}
	tests := map[string]struct {
		serviceErr error
		wantStatus int
		wantBody   string
	}{
		"returns figures":       {wantStatus: http.StatusOK, wantBody: `"bookedInitialDeposits":{"SGD":"350.50","USD":"5.00"}`},
		"maps bad input":        {serviceErr: fmt.Errorf("%w: bad window", accounterrors.ErrBadInput), wantStatus: http.StatusBadRequest},
		"maps forbidden":        {serviceErr: fmt.Errorf("%w: admins only", accounterrors.ErrForbidden), wantStatus: http.StatusForbidden},
		"hides database errors": {serviceErr: errors.New("connection refused"), wantStatus: http.StatusInternalServerError, wantBody: "unable to compute account figures"},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockService(t)
			svc.On("AccountStats", mock.Anything, caller, "2026-09-01", "2026-09-30", "").
				Return(models.AccountStats{
					Total: 2, ByType: map[string]int64{"savings": 2}, ByStatus: map[string]int64{"active": 2},
					ByCurrency: map[string]int64{"SGD": 1, "USD": 1}, ByBranch: map[string]int64{"B1": 2},
					OpenedInWindow: 2, BookedInitialDeposits: map[string]string{"SGD": "350.50", "USD": "5.00"},
					Currencies: []string{"SGD", "USD"}, DailyOpened: []models.DailyValue{},
				}, test.serviceErr).Once()
			req := httptest.NewRequest(http.MethodGet, "/aggregates/accounts/stats?from=2026-09-01&to=2026-09-30", nil)
			res := httptest.NewRecorder()

			New(svc).AccountStats(res, req, caller)
			contract.Check(t, "dashboard-aggregates.yaml", http.MethodGet, "/aggregates/accounts/stats", res)

			assert.Equal(t, test.wantStatus, res.Code)
			assert.Equal(t, "application/json", res.Header().Get("Content-Type"))
			if test.wantBody != "" {
				assert.Contains(t, res.Body.String(), test.wantBody)
			}
			assert.NotContains(t, res.Body.String(), "connection refused")
		})
	}
}

func TestOwnedAccountIDs(t *testing.T) {
	caller := models.Caller{Subject: "agent-sub"}
	svc := servicemock.NewMockService(t)
	svc.On("OwnedAccountIDs", mock.Anything, caller).Return(models.AccountIDs{AccountIDs: []string{}}, nil).Once()
	req := httptest.NewRequest(http.MethodGet, "/aggregates/accounts/ids", nil)
	res := httptest.NewRecorder()

	New(svc).OwnedAccountIDs(res, req, caller)
	contract.Check(t, "dashboard-aggregates.yaml", http.MethodGet, "/aggregates/accounts/ids", res)

	assert.Equal(t, http.StatusOK, res.Code)
	assert.JSONEq(t, `{"accountIds":[]}`, res.Body.String())
}
