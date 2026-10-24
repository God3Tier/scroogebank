package client

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"client-profile-management-service/src/internal/auditnote"
	"client-profile-management-service/src/internal/contract"
	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	servicemock "client-profile-management-service/src/internal/service/client"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestReassignClients(t *testing.T) {
	const authorization = "Bearer admin-token"
	const clientID = "00000000-0000-0000-0000-000000000961"
	const body = `{"to_user_id":"00000000-0000-0000-0000-000000000502","from_user_id":"00000000-0000-0000-0000-000000000501"}`
	request := models.ReassignmentRequest{ToUserID: "00000000-0000-0000-0000-000000000502", FromUserID: "00000000-0000-0000-0000-000000000501"}
	reassign := func(svc *servicemock.MockClientService, body string) (*httptest.ResponseRecorder, *auditnote.Notes) {
		ctx, notes := auditnote.WithNotes(context.Background())
		req := httptest.NewRequest(http.MethodPost, "/api/clients/reassignments", strings.NewReader(body)).WithContext(ctx)
		req.Header.Set("Authorization", authorization)
		res := httptest.NewRecorder()
		New(svc).ReassignClients(res, req)
		contract.Check(t, "clients-api.yaml", http.MethodPost, "/api/clients/reassignments", res)
		return res, notes
	}

	t.Run("says which clients moved and notes each one for the audit", func(t *testing.T) {
		svc := servicemock.NewMockClientService(t)
		moved := []models.Reassignment{{ClientID: clientID, FromAgentID: "agent-from", ToAgentID: "agent-to"}}
		svc.On("ReassignClients", mock.Anything, request, authorization).Return(moved, nil).Once()

		res, notes := reassign(svc, body)

		assert.Equal(t, http.StatusOK, res.Code)
		assert.JSONEq(t, `{"reassigned":1,"client_ids":["`+clientID+`"]}`, res.Body.String())
		assert.Equal(t, moved, notes.Reassignments())
	})

	t.Run("nothing to move is an empty list", func(t *testing.T) {
		svc := servicemock.NewMockClientService(t)
		svc.On("ReassignClients", mock.Anything, request, authorization).Return([]models.Reassignment{}, nil).Once()

		res, notes := reassign(svc, body)

		assert.Equal(t, http.StatusOK, res.Code)
		assert.JSONEq(t, `{"reassigned":0,"client_ids":[]}`, res.Body.String())
		assert.Empty(t, notes.Reassignments())
	})

	t.Run("rejects a malformed body without calling the service", func(t *testing.T) {
		res, _ := reassign(servicemock.NewMockClientService(t), `{`)

		assert.Equal(t, http.StatusBadRequest, res.Code)
	})

	errorsToStatus := map[string]struct {
		err        error
		wantStatus int
	}{
		"maps a bad request":               {err: clienterrors.ErrBadInput, wantStatus: http.StatusBadRequest},
		"maps a receiver that cannot":      {err: clienterrors.ErrInvalidReceiver, wantStatus: http.StatusBadRequest},
		"maps a missing user":              {err: clienterrors.ErrUserNotFound, wantStatus: http.StatusNotFound},
		"maps a missing client":            {err: clienterrors.ErrClientNotFound, wantStatus: http.StatusNotFound},
		"maps too many clients":            {err: clienterrors.ErrTooManyClients, wantStatus: http.StatusConflict},
		"maps a user service outage":       {err: clienterrors.ErrUserServiceUnavailable, wantStatus: http.StatusServiceUnavailable},
		"maps an account service outage":   {err: clienterrors.ErrAccountServiceUnavailable, wantStatus: http.StatusServiceUnavailable},
		"maps an unexpected service error": {err: errors.New("database unavailable"), wantStatus: http.StatusInternalServerError},
	}
	for name, test := range errorsToStatus {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockClientService(t)
			svc.On("ReassignClients", mock.Anything, request, authorization).Return(nil, test.err).Once()

			res, notes := reassign(svc, body)

			assert.Equal(t, test.wantStatus, res.Code)
			assert.Empty(t, notes.Reassignments())
		})
	}
}
