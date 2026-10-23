package account

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	accounterrors "account-service/src/internal/errors"
	servicemock "account-service/src/internal/service/account"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestSetOwner(t *testing.T) {
	const clientID = "00000000-0000-0000-0000-000000000241"
	const body = `{"client_ids":["` + clientID + `"],"owner_agent_id":"agent-new"}`
	setOwner := func(svc *servicemock.MockAccountService, body string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPut, "/ownership/accounts", strings.NewReader(body))
		res := httptest.NewRecorder()
		NewAccountController(svc).SetOwner(res, req)
		return res
	}

	t.Run("returns how many accounts changed owner", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("SetOwner", mock.Anything, []string{clientID}, "agent-new").Return(int64(2), nil).Once()

		res := setOwner(svc, body)

		assert.Equal(t, http.StatusOK, res.Code)
		assert.JSONEq(t, `{"accounts":2}`, res.Body.String())
	})

	t.Run("rejects a malformed body without calling the service", func(t *testing.T) {
		assert.Equal(t, http.StatusBadRequest, setOwner(servicemock.NewMockAccountService(t), `{`).Code)
	})

	t.Run("maps a refused request", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("SetOwner", mock.Anything, mock.Anything, mock.Anything).Return(int64(0), accounterrors.ErrBadInput).Once()

		assert.Equal(t, http.StatusBadRequest, setOwner(svc, body).Code)
	})

	t.Run("maps an unexpected service error", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("SetOwner", mock.Anything, mock.Anything, mock.Anything).Return(int64(0), errors.New("database unavailable")).Once()

		assert.Equal(t, http.StatusInternalServerError, setOwner(svc, body).Code)
	})
}
