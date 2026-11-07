package client

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"client-profile-management-service/src/internal/contract"
	clienterrors "client-profile-management-service/src/internal/errors"
	servicemock "client-profile-management-service/src/internal/service/client"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestVerifyClientIdentity(t *testing.T) {
	const documentID = "0199f1d2-0000-7000-8000-000000000001"
	body := `{"document_ids":["` + documentID + `"]}`
	named := []string{documentID}
	tests := map[string]struct {
		body       string
		ids        []string
		serviceErr error
		wantStatus int
		callSvc    bool
	}{
		"verifies client":                     {body: body, ids: named, wantStatus: http.StatusOK, callSvc: true},
		"verifies without a body":             {body: "", wantStatus: http.StatusOK, callSvc: true},
		"verifies with an empty list":         {body: `{"document_ids":[]}`, ids: []string{}, wantStatus: http.StatusOK, callSvc: true},
		"maps missing client":                 {body: body, ids: named, serviceErr: clienterrors.ErrClientNotFound, wantStatus: http.StatusNotFound, callSvc: true},
		"maps an already verified client":     {body: body, ids: named, serviceErr: clienterrors.ErrAlreadyVerified, wantStatus: http.StatusConflict, callSvc: true},
		"maps another client's document":      {body: body, ids: named, serviceErr: clienterrors.ErrDocumentNotFound, wantStatus: http.StatusBadRequest, callSvc: true},
		"maps bad input":                      {body: body, ids: named, serviceErr: clienterrors.ErrBadInput, wantStatus: http.StatusBadRequest, callSvc: true},
		"maps unexpected service error":       {body: body, ids: named, serviceErr: errors.New("database unavailable"), wantStatus: http.StatusInternalServerError, callSvc: true},
		"refuses a body that is not the list": {body: `{"document_ids":"` + documentID + `"}`, wantStatus: http.StatusBadRequest},
		"refuses a body that is not JSON":     {body: `document_ids=` + documentID, wantStatus: http.StatusBadRequest},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockClientService(t)
			if test.callSvc {
				svc.On("VerifyClientIdentity", mock.Anything, "client-123", "agent-1", test.ids).Return(test.serviceErr).Once()
			}
			req := httptest.NewRequest(http.MethodPost, "/api/clients/verify?id=client-123", strings.NewReader(test.body))
			req.Header.Set("X-Agent-ID", "agent-1")
			res := httptest.NewRecorder()
			New(svc).VerifyClientIdentity(res, req)
			contract.Check(t, "clients-api.yaml", http.MethodPost, "/api/clients/{clientId}/verify", res)
			assert.Equal(t, test.wantStatus, res.Code)
		})
	}
}
