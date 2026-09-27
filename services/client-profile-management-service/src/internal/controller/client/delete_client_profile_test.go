package client

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"
	servicemock "client-profile-management-service/src/internal/service/client"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestDeleteClientProfile(t *testing.T) {
	const id = "client-123"
	tests := map[string]struct {
		serviceErr error
		wantStatus int
	}{
		"deletes client":                {wantStatus: http.StatusOK},
		"maps missing client":           {serviceErr: clienterrors.ErrClientNotFound, wantStatus: http.StatusNotFound},
		"maps unexpected service error": {serviceErr: errors.New("database unavailable"), wantStatus: http.StatusInternalServerError},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockClientService(t)
			svc.On("DeleteClientProfile", mock.Anything, id).Return(test.serviceErr).Once()
			req := httptest.NewRequest(http.MethodDelete, "/api/clients?id="+id, nil)
			res := httptest.NewRecorder()
			New(svc).DeleteClientProfile(res, req)
			assert.Equal(t, test.wantStatus, res.Code)
		})
	}
}
