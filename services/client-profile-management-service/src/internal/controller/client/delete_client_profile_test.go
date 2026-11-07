package client

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"client-profile-management-service/src/internal/contract"
	clienterrors "client-profile-management-service/src/internal/errors"
	servicemock "client-profile-management-service/src/internal/service/client"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestDeleteClientProfile(t *testing.T) {
	const id = "client-123"
	const authorization = "Bearer caller-token"
	tests := map[string]struct {
		serviceErr error
		wantStatus int
	}{
		"deletes client":                {wantStatus: http.StatusOK},
		"maps missing client":           {serviceErr: clienterrors.ErrClientNotFound, wantStatus: http.StatusNotFound},
		"maps client with accounts":     {serviceErr: clienterrors.ErrClientHasAccounts, wantStatus: http.StatusConflict},
		"maps account service outage":   {serviceErr: clienterrors.ErrAccountServiceUnavailable, wantStatus: http.StatusServiceUnavailable},
		"maps unexpected service error": {serviceErr: errors.New("database unavailable"), wantStatus: http.StatusInternalServerError},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockClientService(t)
			svc.On("DeleteClientProfile", mock.Anything, id, authorization).Return(test.serviceErr).Once()
			req := httptest.NewRequest(http.MethodDelete, "/api/clients?id="+id, nil)
			req.Header.Set("Authorization", authorization)
			res := httptest.NewRecorder()
			New(svc).DeleteClientProfile(res, req)
			contract.Check(t, "clients-api.yaml", http.MethodDelete, "/api/clients/{clientId}", res)
			assert.Equal(t, test.wantStatus, res.Code)
		})
	}
}
