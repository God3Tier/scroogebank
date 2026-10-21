package clients

import (
	accounterrors "account-service/src/internal/errors"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const clientID = "00000000-0000-0000-0000-000000000201"

func TestLookup(t *testing.T) {
	profile := func(fields string) string { return `{"client_id":"` + clientID + `",` + fields + `}` }
	tests := map[string]struct {
		status  int
		body    string
		want    Client
		wantErr error
	}{
		"returns the owner of a verified client": {
			status: http.StatusOK, body: profile(`"created_by_agent_id":"agent-sub-1","verification_status":"VERIFIED"`),
			want: Client{OwnerAgentID: "agent-sub-1", Verified: true},
		},
		// Clients verified before ADR-0041 hold the status in lower case.
		"accepts a lower-case verified status": {
			status: http.StatusOK, body: profile(`"created_by_agent_id":"agent-sub-1","verification_status":"verified"`),
			want: Client{OwnerAgentID: "agent-sub-1", Verified: true},
		},
		"reports an unverified client": {
			status: http.StatusOK, body: profile(`"created_by_agent_id":"agent-sub-1","verification_status":"UNVERIFIED"`),
			want: Client{OwnerAgentID: "agent-sub-1"},
		},
		"treats a missing status as unverified": {
			status: http.StatusOK, body: profile(`"created_by_agent_id":"agent-sub-1"`),
			want: Client{OwnerAgentID: "agent-sub-1"},
		},
		"maps a missing or foreign client":   {status: http.StatusNotFound, body: `client not found`, wantErr: accounterrors.ErrClientNotFound},
		"fails closed on a server error":     {status: http.StatusInternalServerError, wantErr: accounterrors.ErrClientServiceUnavailable},
		"fails closed on a rejected token":   {status: http.StatusUnauthorized, wantErr: accounterrors.ErrClientServiceUnavailable},
		"fails closed on an unreadable body": {status: http.StatusOK, body: `{`, wantErr: accounterrors.ErrClientServiceUnavailable},
		"fails closed without an owner":      {status: http.StatusOK, body: profile(`"verification_status":"VERIFIED"`), wantErr: accounterrors.ErrClientServiceUnavailable},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				assert.Equal(t, http.MethodGet, r.Method)
				assert.Equal(t, "/api/clients/"+clientID, r.URL.Path)
				// The caller's own token is forwarded, so the client service
				// applies its ownership rule to the caller, not to this service.
				assert.Equal(t, "Bearer caller-token", r.Header.Get("Authorization"))
				w.WriteHeader(test.status)
				_, _ = w.Write([]byte(test.body))
			}))
			defer server.Close()

			client, err := NewHTTPDirectory(server.URL+"/", time.Second).Lookup(context.Background(), clientID, "Bearer caller-token")

			if test.wantErr != nil {
				require.ErrorIs(t, err, test.wantErr)
				assert.Equal(t, Client{}, client)
				return
			}
			require.NoError(t, err)
			assert.Equal(t, test.want, client)
		})
	}
}

func TestLookupFailsClosedWhenTheClientServiceIsSlow(t *testing.T) {
	release := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-release
	}))
	defer server.Close()
	defer close(release)

	_, err := NewHTTPDirectory(server.URL, 50*time.Millisecond).Lookup(context.Background(), clientID, "Bearer caller-token")

	require.ErrorIs(t, err, accounterrors.ErrClientServiceUnavailable)
}

func TestLookupFailsClosedWhenTheClientServiceIsDown(t *testing.T) {
	server := httptest.NewServer(http.NotFoundHandler())
	url := server.URL
	server.Close()

	_, err := NewHTTPDirectory(url, time.Second).Lookup(context.Background(), clientID, "Bearer caller-token")

	require.ErrorIs(t, err, accounterrors.ErrClientServiceUnavailable)
}
