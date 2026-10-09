package aggregate

import (
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	servicemock "client-profile-management-service/src/internal/service/aggregate"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestClientStats(t *testing.T) {
	caller := models.Caller{Subject: "agent-sub"}
	tests := map[string]struct {
		serviceErr error
		wantStatus int
		wantBody   string
	}{
		"returns figures":       {wantStatus: http.StatusOK, wantBody: `"createdInWindow":4`},
		"maps bad input":        {serviceErr: fmt.Errorf("%w: bad window", clienterrors.ErrBadInput), wantStatus: http.StatusBadRequest},
		"maps forbidden":        {serviceErr: fmt.Errorf("%w: admins only", clienterrors.ErrForbidden), wantStatus: http.StatusForbidden},
		"hides database errors": {serviceErr: errors.New("connection refused"), wantStatus: http.StatusInternalServerError, wantBody: "unable to compute client figures"},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockService(t)
			svc.On("ClientStats", mock.Anything, caller, "2026-09-01", "2026-09-30", "agent").
				Return(models.ClientStats{CreatedInWindow: 4}, test.serviceErr).Once()
			req := httptest.NewRequest(http.MethodGet, "/aggregates/clients/stats?from=2026-09-01&to=2026-09-30&groupBy=agent", nil)
			res := httptest.NewRecorder()

			New(svc).ClientStats(res, req, caller)

			assert.Equal(t, test.wantStatus, res.Code)
			assert.Equal(t, "application/json", res.Header().Get("Content-Type"))
			if test.wantBody != "" {
				assert.Contains(t, res.Body.String(), test.wantBody)
			}
			assert.NotContains(t, res.Body.String(), "connection refused")
		})
	}
}

func TestTeamComparison(t *testing.T) {
	caller := models.Caller{Subject: "agent-sub"}
	tests := map[string]struct {
		serviceErr error
		wantStatus int
		wantBody   string
	}{
		"returns the comparison":     {wantStatus: http.StatusOK, wantBody: `"teamMedian":null`},
		"maps an unsupported window": {serviceErr: fmt.Errorf("%w: days must be 7, 30 or 90", clienterrors.ErrBadInput), wantStatus: http.StatusBadRequest},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockService(t)
			svc.On("TeamComparison", mock.Anything, caller, "30").
				Return(models.TeamComparison{Days: 30, ClientsOnboarded: 2, CohortSize: 3}, test.serviceErr).Once()
			req := httptest.NewRequest(http.MethodGet, "/aggregates/clients/team-comparison?days=30", nil)
			res := httptest.NewRecorder()

			New(svc).TeamComparison(res, req, caller)

			assert.Equal(t, test.wantStatus, res.Code)
			if test.wantBody != "" {
				assert.Contains(t, res.Body.String(), test.wantBody)
			}
		})
	}
}

func TestActionItems(t *testing.T) {
	caller := models.Caller{Subject: "agent-sub"}
	svc := servicemock.NewMockService(t)
	svc.On("ActionItems", mock.Anything, caller, "14").
		Return(models.ClientActionItems{StaleUnverified: []models.ClientRef{}, StaleUnverifiedTotal: 0}, nil).Once()
	req := httptest.NewRequest(http.MethodGet, "/aggregates/clients/action-items?staleDays=14", nil)
	res := httptest.NewRecorder()

	New(svc).ActionItems(res, req, caller)

	assert.Equal(t, http.StatusOK, res.Code)
	assert.JSONEq(t, `{"staleUnverified":[],"staleUnverifiedTotal":0}`, res.Body.String())
}
