package accounts

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const accountID = "a0000000-0000-0000-0000-000000000001"

// accountService answers with status and body, and records what it was asked.
func accountService(t *testing.T, status int, body string) (Directory, *http.Request) {
	t.Helper()
	seen := &http.Request{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		*seen = *r.Clone(context.Background())
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(server.Close)
	return NewHTTPDirectory(server.URL+"/", time.Second), seen
}

func TestCheckAccess(t *testing.T) {
	t.Run("admits the caller account-service admits, forwarding their token", func(t *testing.T) {
		directory, seen := accountService(t, http.StatusOK, `{"id":"`+accountID+`"}`)

		require.NoError(t, directory.CheckAccess(context.Background(), accountID, "caller-token"))

		assert.Equal(t, "/api/accounts/"+accountID, seen.URL.Path)
		assert.Equal(t, "Bearer caller-token", seen.Header.Get("Authorization"))
	})

	for _, status := range []int{http.StatusUnauthorized, http.StatusForbidden, http.StatusNotFound} {
		t.Run("passes on account-service's "+http.StatusText(status), func(t *testing.T) {
			directory, _ := accountService(t, status, `{"error":"resource not found"}`)

			err := directory.CheckAccess(context.Background(), accountID, "caller-token")

			assert.Equal(t, Refused{Status: status}, err)
		})
	}

	t.Run("treats any other answer as none", func(t *testing.T) {
		directory, _ := accountService(t, http.StatusInternalServerError, `{"error":"boom"}`)

		assert.ErrorIs(t, directory.CheckAccess(context.Background(), accountID, "caller-token"), ErrUnavailable)
	})

	t.Run("treats an unreachable account-service as no answer", func(t *testing.T) {
		directory := NewHTTPDirectory("http://127.0.0.1:1", time.Second)

		assert.ErrorIs(t, directory.CheckAccess(context.Background(), accountID, "caller-token"), ErrUnavailable)
	})
}

func TestOwnedAccountIDs(t *testing.T) {
	t.Run("returns the caller's accounts", func(t *testing.T) {
		directory, seen := accountService(t, http.StatusOK, `{"accountIds":["`+accountID+`"]}`)

		ids, err := directory.OwnedAccountIDs(context.Background(), "caller-token")

		require.NoError(t, err)
		assert.Equal(t, []string{accountID}, ids)
		assert.Equal(t, "/aggregates/accounts/ids", seen.URL.Path)
		assert.Equal(t, "Bearer caller-token", seen.Header.Get("Authorization"))
	})

	t.Run("returns an empty list for an agent with no accounts", func(t *testing.T) {
		directory, _ := accountService(t, http.StatusOK, `{"accountIds":[]}`)

		ids, err := directory.OwnedAccountIDs(context.Background(), "caller-token")

		require.NoError(t, err)
		assert.Empty(t, ids)
	})

	t.Run("refuses an answer with no account list", func(t *testing.T) {
		directory, _ := accountService(t, http.StatusOK, `{}`)

		_, err := directory.OwnedAccountIDs(context.Background(), "caller-token")

		assert.ErrorIs(t, err, ErrUnavailable)
	})

	t.Run("passes on account-service's refusal", func(t *testing.T) {
		directory, _ := accountService(t, http.StatusForbidden, `{"error":"ADMIN or AGENT role required"}`)

		_, err := directory.OwnedAccountIDs(context.Background(), "caller-token")

		var refused Refused
		require.True(t, errors.As(err, &refused))
		assert.Equal(t, http.StatusForbidden, refused.Status)
	})
}
