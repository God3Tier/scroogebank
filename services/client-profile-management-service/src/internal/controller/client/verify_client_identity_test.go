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

func TestVerifyClientIdentity(t *testing.T) {
	tests := map[string]struct {
		id         string
		serviceErr error
		wantStatus int
		callSvc    bool
	}{
		"verifies client":               {id: "client-123", wantStatus: http.StatusOK, callSvc: true},
		"maps missing client":           {id: "client-123", serviceErr: clienterrors.ErrClientNotFound, wantStatus: http.StatusNotFound, callSvc: true},
		"maps unexpected service error": {id: "client-123", serviceErr: errors.New("database unavailable"), wantStatus: http.StatusInternalServerError, callSvc: true},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockClientService(t)
			if test.callSvc {
				svc.On("VerifyClientIdentity", mock.Anything, test.id).Return(test.serviceErr).Once()
			}
			req := httptest.NewRequest(http.MethodPost, "/api/clients/verify?id="+test.id, nil)
			res := httptest.NewRecorder()
			New(svc).VerifyClientIdentity(res, req)
			assert.Equal(t, test.wantStatus, res.Code)
		})
	}
}
