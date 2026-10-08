package account

import (
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	accounterrors "account-service/src/internal/errors"
	orm "account-service/src/internal/repository/orm"
	servicemock "account-service/src/internal/service/account"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

func TestLookupAccounts(t *testing.T) {
	const accountID = "00000000-0000-0000-0000-000000000231"
	const clientID = "00000000-0000-0000-0000-000000000203"
	lookup := func(svc *servicemock.MockAccountService, body string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "/internal/accounts/lookup", strings.NewReader(body))
		res := httptest.NewRecorder()
		NewAccountController(svc).LookupAccounts(res, req)
		return res
	}

	t.Run("returns each found account with its client", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("LookupAccounts", mock.Anything, []string{accountID}).
			Return(orm.AccountSlice{{ID: accountID, ClientID: clientID}}, nil).Once()

		res := lookup(svc, `{"ids":["`+accountID+`"]}`)

		assert.Equal(t, http.StatusOK, res.Code)
		assert.JSONEq(t, fmt.Sprintf(`{"accounts":[{"id":%q,"client_id":%q}]}`, accountID, clientID), res.Body.String())
	})

	t.Run("nothing found is an empty list", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("LookupAccounts", mock.Anything, []string{accountID}).Return(orm.AccountSlice{}, nil).Once()

		res := lookup(svc, `{"ids":["`+accountID+`"]}`)

		assert.Equal(t, http.StatusOK, res.Code)
		assert.JSONEq(t, `{"accounts":[]}`, res.Body.String())
	})

	t.Run("rejects a malformed body without calling the service", func(t *testing.T) {
		res := lookup(servicemock.NewMockAccountService(t), `{`)

		assert.Equal(t, http.StatusBadRequest, res.Code)
	})

	t.Run("maps a refused lookup", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("LookupAccounts", mock.Anything, mock.Anything).Return(nil, accounterrors.ErrBadInput).Once()

		assert.Equal(t, http.StatusBadRequest, lookup(svc, `{"ids":[]}`).Code)
	})

	t.Run("maps an unexpected service error", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("LookupAccounts", mock.Anything, mock.Anything).Return(nil, errors.New("database unavailable")).Once()

		assert.Equal(t, http.StatusInternalServerError, lookup(svc, `{"ids":[]}`).Code)
	})
}
