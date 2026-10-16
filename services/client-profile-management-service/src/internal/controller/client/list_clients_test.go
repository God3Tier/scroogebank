package client

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	servicemock "client-profile-management-service/src/internal/service/client"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestListClients(t *testing.T) {
	caller := models.Caller{Subject: "agent-sub"}
	page := models.ClientPage{
		Content: []models.Client{{FirstName: "Alice"}}, Page: 1, Size: 5, TotalElements: 6, TotalPages: 2,
	}
	tests := map[string]struct {
		query            string
		wantPage, wantSz int
		callSvc          bool
		serviceErr       error
		wantStatus       int
		wantBody         string
	}{
		"returns a page":              {query: "?page=1&size=5", wantPage: 1, wantSz: 5, callSvc: true, wantStatus: http.StatusOK, wantBody: `"totalElements":6`},
		"defaults page and size":      {query: "", wantPage: 0, wantSz: defaultPageSize, callSvc: true, wantStatus: http.StatusOK, wantBody: `"first_name":"Alice"`},
		"rejects a page that is text": {query: "?page=one", wantStatus: http.StatusBadRequest},
		"rejects a size that is text": {query: "?size=many", wantStatus: http.StatusBadRequest},
		"maps bad input":              {query: "?size=500", wantPage: 0, wantSz: 500, callSvc: true, serviceErr: clienterrors.ErrBadInput, wantStatus: http.StatusBadRequest},
		"maps forbidden":              {query: "", wantPage: 0, wantSz: defaultPageSize, callSvc: true, serviceErr: clienterrors.ErrForbidden, wantStatus: http.StatusForbidden},
		"maps unexpected error":       {query: "", wantPage: 0, wantSz: defaultPageSize, callSvc: true, serviceErr: errors.New("database unavailable"), wantStatus: http.StatusInternalServerError},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockClientService(t)
			if test.callSvc {
				svc.On("ListClients", mock.Anything, caller, test.wantPage, test.wantSz).Return(page, test.serviceErr).Once()
			}
			req := httptest.NewRequest(http.MethodGet, "/api/clients"+test.query, nil)
			res := httptest.NewRecorder()
			New(svc).ListClients(res, req, caller)
			assert.Equal(t, test.wantStatus, res.Code)
			if test.wantBody != "" {
				assert.Contains(t, res.Body.String(), test.wantBody)
			}
		})
	}
}
