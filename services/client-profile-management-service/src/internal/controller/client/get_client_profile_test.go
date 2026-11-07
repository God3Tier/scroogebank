package client

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"uuid"

	"client-profile-management-service/src/internal/contract"
	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	servicemock "client-profile-management-service/src/internal/service/client"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestGetClientProfile(t *testing.T) {
	const id = "123e4567-e89b-12d3-a456-426614174000"
	wantClientID, err := uuid.Parse(id)
	if err != nil {
		t.Fatal(err)
	}
	wantClient := models.Client{ClientID: wantClientID, FirstName: "Alice", LastName: "Smith"}
	tests := map[string]struct {
		serviceErr error
		wantStatus int
		callSvc    bool
		wantBody   string
	}{
		"returns client":                {wantStatus: http.StatusOK, callSvc: true, wantBody: `"first_name":"Alice"`},
		"maps missing client":           {serviceErr: clienterrors.ErrClientNotFound, wantStatus: http.StatusNotFound, callSvc: true},
		"maps unexpected service error": {serviceErr: errors.New("database unavailable"), wantStatus: http.StatusInternalServerError, callSvc: true},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockClientService(t)
			if test.callSvc {
				svc.On("GetClientProfile", mock.Anything, id).Return(wantClient, test.serviceErr).Once()
			}
			req := httptest.NewRequest(http.MethodGet, "/api/clients?id="+id, nil)
			res := httptest.NewRecorder()
			New(svc).GetClientProfile(res, req)
			contract.Check(t, "clients-api.yaml", http.MethodGet, "/api/clients/{clientId}", res)
			assert.Equal(t, test.wantStatus, res.Code)
			if test.wantBody != "" {
				assert.Contains(t, res.Body.String(), test.wantBody)
			}
		})
	}
}
