package account

import (
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

func TestLookupAlertAccounts(t *testing.T) {
	const accountID = "00000000-0000-0000-0000-000000000231"
	const clientID = "00000000-0000-0000-0000-000000000203"
	lookup := func(svc *servicemock.MockAccountService, body string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "/internal/alerts/accounts/lookup", strings.NewReader(body))
		res := httptest.NewRecorder()
		NewAccountController(svc).LookupAlertAccounts(res, req)
		return res
	}

	t.Run("returns the client, type, status, currency and current owner", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("LookupAccounts", mock.Anything, []string{accountID}).Return(orm.AccountSlice{{
			ID: accountID, ClientID: clientID, AccountType: "INVESTMENT", Status: "ACTIVE",
			Currency: "SGD", OwnerAgentID: "agent-sub-1",
		}}, nil).Once()

		res := lookup(svc, `{"ids":["`+accountID+`"]}`)

		assert.Equal(t, http.StatusOK, res.Code)
		assert.JSONEq(t, fmt.Sprintf(
			`{"items":[{"id":%q,"client_id":%q,"type":"INVESTMENT","status":"ACTIVE","currency":"SGD","owner_agent_id":"agent-sub-1"}]}`,
			accountID, clientID), res.Body.String())
	})

	t.Run("an unknown account is left out, not an error", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("LookupAccounts", mock.Anything, []string{accountID}).Return(orm.AccountSlice{}, nil).Once()

		res := lookup(svc, `{"ids":["`+accountID+`"]}`)

		assert.Equal(t, http.StatusOK, res.Code)
		assert.JSONEq(t, `{"items":[]}`, res.Body.String())
	})

	t.Run("repeated ids are looked up once", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("LookupAccounts", mock.Anything, []string{accountID}).Return(orm.AccountSlice{}, nil).Once()

		assert.Equal(t, http.StatusOK, lookup(svc, `{"ids":["`+accountID+`","`+accountID+`"]}`).Code)
	})

	t.Run("more than 200 distinct ids is refused without calling the service", func(t *testing.T) {
		ids := make([]string, 0, 201)
		for i := 0; i < 201; i++ {
			ids = append(ids, fmt.Sprintf("%q", fmt.Sprintf("00000000-0000-0000-0000-%012d", i)))
		}
		res := lookup(servicemock.NewMockAccountService(t), `{"ids":[`+strings.Join(ids, ",")+`]}`)

		assert.Equal(t, http.StatusBadRequest, res.Code)
	})

	t.Run("rejects a malformed body", func(t *testing.T) {
		assert.Equal(t, http.StatusBadRequest, lookup(servicemock.NewMockAccountService(t), `{`).Code)
	})

	t.Run("maps a refused lookup and a failure", func(t *testing.T) {
		svc := servicemock.NewMockAccountService(t)
		svc.On("LookupAccounts", mock.Anything, mock.Anything).Return(nil, accounterrors.ErrBadInput).Once()
		assert.Equal(t, http.StatusBadRequest, lookup(svc, `{"ids":["x"]}`).Code)

		svc = servicemock.NewMockAccountService(t)
		svc.On("LookupAccounts", mock.Anything, mock.Anything).Return(nil, fmt.Errorf("database down")).Once()
		res := lookup(svc, `{"ids":["x"]}`)
		assert.Equal(t, http.StatusInternalServerError, res.Code)
		assert.NotContains(t, res.Body.String(), "database down")
	})
}
