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

func TestUpdateClientInformation(t *testing.T) {
	const id = "123e4567-e89b-12d3-a456-426614174000"
	const body = `{"first_name":"Alice","last_name":"Smith","date_of_birth":"1990-05-10T00:00:00Z","gender":"female","email":"alice@example.test","phone_number":"+6561234567","address":"1 Example Street","city":"Singapore","state":"Singapore","country":"Singapore","postal_code":"123456"}`
	tests := map[string]struct {
		id         string
		body       string
		serviceErr error
		wantStatus int
		callSvc    bool
	}{
		"updates client":                {id: id, body: body, wantStatus: http.StatusOK, callSvc: true},
		"rejects malformed JSON":        {id: id, body: `{`, wantStatus: http.StatusBadRequest},
		"rejects malformed ID":          {id: "bad-id", body: body, wantStatus: http.StatusBadRequest},
		"maps invalid client":           {id: id, body: body, serviceErr: clienterrors.ErrBadInput, wantStatus: http.StatusBadRequest, callSvc: true},
		"maps unexpected service error": {id: id, body: body, serviceErr: errors.New("database unavailable"), wantStatus: http.StatusInternalServerError, callSvc: true},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockClientService(t)
			if test.callSvc {
				svc.On("UpdateClientInformation", mock.Anything, mock.MatchedBy(func(got models.Client) bool {
					return got.ClientID.String() == id && got.FirstName == "Alice"
				})).Return(test.serviceErr).Once()
			}
			req := httptest.NewRequest(http.MethodPut, "/api/clients?id="+test.id, strings.NewReader(test.body))
			res := httptest.NewRecorder()
			New(svc).UpdateClientInformation(res, req)
			assert.Equal(t, test.wantStatus, res.Code)
		})
	}
}
