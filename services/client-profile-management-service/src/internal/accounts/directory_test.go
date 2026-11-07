package accounts

import (
	clienterrors "client-profile-management-service/src/internal/errors"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const clientID = "00000000-0000-0000-0000-000000000201"

func TestHasAccounts(t *testing.T) {
	tests := map[string]struct {
		status  int
		body    string
		want    bool
		wantErr error
	}{
		"reports accounts":                   {status: http.StatusOK, body: `[{"id":"00000000-0000-0000-0000-000000000231"}]`, want: true},
		"reports no accounts":                {status: http.StatusOK, body: `[]`},
		"fails closed on a server error":     {status: http.StatusInternalServerError, wantErr: clienterrors.ErrAccountServiceUnavailable},
		"fails closed on a rejected token":   {status: http.StatusUnauthorized, wantErr: clienterrors.ErrAccountServiceUnavailable},
		"fails closed on an unreadable body": {status: http.StatusOK, body: `{`, wantErr: clienterrors.ErrAccountServiceUnavailable},
		"fails closed on a null list":        {status: http.StatusOK, body: `null`, wantErr: clienterrors.ErrAccountServiceUnavailable},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				assert.Equal(t, http.MethodGet, r.Method)
				assert.Equal(t, "/api/accounts", r.URL.Path)
				assert.Equal(t, clientID, r.URL.Query().Get("clientId"))
				assert.Equal(t, "Bearer caller-token", r.Header.Get("Authorization"))
				w.WriteHeader(test.status)
				_, _ = w.Write([]byte(test.body))
			}))
			defer server.Close()

			got, err := NewHTTPDirectory(server.URL+"/", time.Second).HasAccounts(context.Background(), clientID, "Bearer caller-token")

			if test.wantErr != nil {
				require.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)
			assert.Equal(t, test.want, got)
		})
	}
}

func TestHasAccountsFailsClosedWhenTheAccountServiceIsDown(t *testing.T) {
	server := httptest.NewServer(http.NotFoundHandler())
	url := server.URL
	server.Close()

	_, err := NewHTTPDirectory(url, time.Second).HasAccounts(context.Background(), clientID, "Bearer caller-token")

	require.ErrorIs(t, err, clienterrors.ErrAccountServiceUnavailable)
}

func TestSetOwner(t *testing.T) {
	tests := map[string]struct {
		status  int
		wantErr error
	}{
		"confirms the move":                 {status: http.StatusOK},
		"fails closed on a server error":    {status: http.StatusInternalServerError, wantErr: clienterrors.ErrAccountServiceUnavailable},
		"fails closed on a refused token":   {status: http.StatusForbidden, wantErr: clienterrors.ErrAccountServiceUnavailable},
		"fails closed on a refused request": {status: http.StatusBadRequest, wantErr: clienterrors.ErrAccountServiceUnavailable},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				assert.Equal(t, http.MethodPut, r.Method)
				assert.Equal(t, "/ownership/accounts", r.URL.Path)
				assert.Equal(t, "Bearer admin-token", r.Header.Get("Authorization"))
				body, err := io.ReadAll(r.Body)
				require.NoError(t, err)
				assert.JSONEq(t, `{"client_ids":["`+clientID+`"],"owner_agent_id":"agent-new"}`, string(body))
				w.WriteHeader(test.status)
				_, _ = w.Write([]byte(`{"accounts":1}`))
			}))
			defer server.Close()

			err := NewHTTPDirectory(server.URL, time.Second).SetOwner(context.Background(), []string{clientID}, "agent-new", "Bearer admin-token")

			if test.wantErr != nil {
				require.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)
		})
	}
}

func TestSetOwnerFailsClosedWhenTheAccountServiceIsDown(t *testing.T) {
	server := httptest.NewServer(http.NotFoundHandler())
	url := server.URL
	server.Close()

	err := NewHTTPDirectory(url, time.Second).SetOwner(context.Background(), []string{clientID}, "agent-new", "Bearer admin-token")

	require.ErrorIs(t, err, clienterrors.ErrAccountServiceUnavailable)
}
