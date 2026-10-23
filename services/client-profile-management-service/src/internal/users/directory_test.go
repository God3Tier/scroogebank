package users

import (
	clienterrors "client-profile-management-service/src/internal/errors"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const userID = "00000000-0000-0000-0000-000000000501"

func TestLookup(t *testing.T) {
	tests := map[string]struct {
		status  int
		body    string
		want    User
		wantErr error
	}{
		"returns the user's sub, role and state": {
			status: http.StatusOK, body: `{"id":"` + userID + `","idpSub":"agent-sub-1","role":"agent","enabled":true,"email":"synthetic@example.test"}`,
			want: User{Subject: "agent-sub-1", Role: "AGENT", Enabled: true},
		},
		"reports a disabled user": {
			status: http.StatusOK, body: `{"idpSub":"agent-sub-1","role":"AGENT","enabled":false}`,
			want: User{Subject: "agent-sub-1", Role: "AGENT"},
		},
		"reports a missing user":               {status: http.StatusNotFound, wantErr: clienterrors.ErrUserNotFound},
		"fails closed on a server error":       {status: http.StatusInternalServerError, wantErr: clienterrors.ErrUserServiceUnavailable},
		"fails closed on a refused token":      {status: http.StatusForbidden, wantErr: clienterrors.ErrUserServiceUnavailable},
		"fails closed on an unreadable body":   {status: http.StatusOK, body: `{`, wantErr: clienterrors.ErrUserServiceUnavailable},
		"fails closed on a user without a sub": {status: http.StatusOK, body: `{"role":"AGENT","enabled":true}`, wantErr: clienterrors.ErrUserServiceUnavailable},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				assert.Equal(t, http.MethodGet, r.Method)
				assert.Equal(t, "/api/users/"+userID, r.URL.Path)
				assert.Equal(t, "Bearer admin-token", r.Header.Get("Authorization"))
				w.WriteHeader(test.status)
				_, _ = w.Write([]byte(test.body))
			}))
			defer server.Close()

			got, err := NewHTTPDirectory(server.URL+"/", time.Second).Lookup(context.Background(), userID, "Bearer admin-token")

			if test.wantErr != nil {
				require.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)
			assert.Equal(t, test.want, got)
		})
	}
}

func TestLookupFailsClosedWhenTheUserServiceIsDown(t *testing.T) {
	server := httptest.NewServer(http.NotFoundHandler())
	url := server.URL
	server.Close()

	_, err := NewHTTPDirectory(url, time.Second).Lookup(context.Background(), userID, "Bearer admin-token")

	require.ErrorIs(t, err, clienterrors.ErrUserServiceUnavailable)
}
