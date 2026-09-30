package client

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	servicemock "client-profile-management-service/src/internal/service/client"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestCreateClientProfile(t *testing.T) {
	const body = `{"first_name":"Alice","last_name":"Smith","date_of_birth":"1990-05-10T00:00:00Z","gender":"female","email":"alice@example.test","phone_number":"+6561234567","address":"1 Example Street","city":"Singapore","state":"Singapore","country":"Singapore","postal_code":"123456"}`
	tests := map[string]struct {
		body       string
		serviceErr error
		wantStatus int
		callSvc    bool
	}{
		"creates client":                {body: body, wantStatus: http.StatusCreated, callSvc: true},
		"rejects malformed JSON":        {body: `{`, wantStatus: http.StatusBadRequest},
		"maps duplicate client":         {body: body, serviceErr: clienterrors.ErrDuplicateClient, wantStatus: http.StatusConflict, callSvc: true},
		"maps invalid client":           {body: body, serviceErr: clienterrors.ErrBadInput, wantStatus: http.StatusBadRequest, callSvc: true},
		"maps unexpected service error": {body: body, serviceErr: errors.New("database unavailable"), wantStatus: http.StatusInternalServerError, callSvc: true},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockClientService(t)
			if test.callSvc {
				svc.On("CreateClientProfile", mock.Anything, mock.MatchedBy(func(got models.Client) bool {
					return got.FirstName == "Alice" && got.LastName == "Smith" && got.Email == "alice@example.test"
				})).Return(test.serviceErr).Once()
			}
			req := httptest.NewRequest(http.MethodPost, "/api/clients", strings.NewReader(test.body))
			res := httptest.NewRecorder()
			New(svc).CreateClientProfile(res, req)
			assert.Equal(t, test.wantStatus, res.Code)
		})
	}
}
